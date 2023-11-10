package com.rockthejvm.part4coordination

import cats.effect.kernel.Outcome.{Canceled, Errored, Succeeded}
import cats.effect.{Deferred, IO, IOApp, Ref}
import cats.syntax.parallel.*
import com.rockthejvm.utils.*

import scala.collection.immutable.Queue
import scala.concurrent.duration.*
import scala.util.Random

abstract class Mutex {
  def acquire: IO[Unit]
  def release: IO[Unit]
}

object Mutex {
  type Signal = Deferred[IO, Unit]

  final case class State(locked: Boolean, waiting: Queue[Signal])

  val unlocked = State(locked = false, Queue())

  def create: IO[Mutex] = Ref[IO].of(unlocked).map(createMutexWithCancellation)

  def createMutexWithCancellation(refState: Ref[IO, State]): Mutex =
    new Mutex {
      override def acquire: IO[Unit] = IO.uncancelable { poll =>
        Deferred[IO, Unit].flatMap { signal =>
          val cleanup = refState.modify { case State(locked, queue) =>
            val newQueue = queue.filterNot(_ eq signal)
            State(locked, newQueue) -> release
          }.flatten

          refState.modify {
            case State(false, _)    => State(true, Queue())         -> IO.unit
            case State(true, queue) => State(true, queue :+ signal) -> poll(signal.get).onCancel(cleanup)
          }.flatten
        }
      }

      override def release: IO[Unit] =
        refState.modify {
          case state @ State(false, _)             => unlocked -> IO.unit
          case State(true, queue) if queue.isEmpty => unlocked -> IO.unit
          case State(true, queue) =>
            val (signal, newQueue) = queue.dequeue
            State(true, newQueue) -> signal.complete(()).void
        }.flatten
    }

  def createSimpleMutex(refState: Ref[IO, State]): Mutex =
    new Mutex {
      /*
        Change the state of the Ref:
        - if the mutex is currently unlocked, state becomes (true, [])
        - if the mutex is locked, state becomes (true, queue + new signal) AND WAIT ON THAT SIGNAL
       */
      override def acquire: IO[Unit] = Deferred[IO, Unit].flatMap { signal =>
        refState.modify {
          case State(false, _)    => State(true, Queue())         -> IO.unit
          case State(true, queue) => State(true, queue :+ signal) -> signal.get
        }.flatten
      }

      /*
        Change the state of the Ref:
        - if the mutex is unlocked, leave the state unchanged
        - if the mutex is locked:
          - if the queue is empty, unlock the mutex, i.e. state becomes (false, [])
          - if the queue is not empty, take a signal out of the queue and complete it (unblocking a fiber waiting on it)
       */
      override def release: IO[Unit] =
        refState.modify {
          case state @ State(false, _)             => unlocked -> IO.unit
          case State(true, queue) if queue.isEmpty => unlocked -> IO.unit
          case State(true, queue) =>
            val (signal, newQueue) = queue.dequeue
            State(true, newQueue) -> signal.complete(()).void
        }.flatten
    }
}

object MutexPlayground extends IOApp.Simple {

  def criticalTask(): IO[Int] = IO.sleep(1.second) >> IO(Random.nextInt(100))

  def createLockingTask(id: Int, mutex: Mutex): IO[Int] = for {
    _ <- IO(s"[task $id] waiting for permission...").debugs
    _ <- mutex.acquire // blocks if the mutex has been acquired by some other fiber
    // critical section
    _   <- IO(s"[task $id] working...").debugs
    res <- criticalTask()
    _   <- IO(s"[task $id] got result: $res").debugs
    // critical section end
    _ <- mutex.release
    _ <- IO(s"[task $id] lock removed").debugs
  } yield res

  def demoNonLockingTask(): IO[List[Int]] = for {
    mutex   <- Mutex.create
    results <- (1 to 10).toList.parTraverse(id => createLockingTask(id, mutex))
  } yield results
  // only one task can proceed at a task

  def createCancellingTask(id: Int, mutex: Mutex): IO[Int] =
    if (id % 2 == 0) createLockingTask(id, mutex)
    else
      for {
        fib <- createLockingTask(id, mutex).onCancel(IO("s[task $id] received cancellation!").debugs.void).start
        _   <- IO.sleep(2.seconds) >> fib.cancel
        out <- fib.join
        result <- out match {
          case Succeeded(effect) => effect
          case Errored(_)        => IO(-1)
          case Canceled()        => IO(-2)
        }
      } yield result

  def demoCancellingTasks() = for {
    mutex   <- Mutex.create
    results <- (1 to 10).toList.parTraverse(id => createCancellingTask(id, mutex))
  } yield results

  override def run: IO[Unit] = demoCancellingTasks().debugs.void
}
