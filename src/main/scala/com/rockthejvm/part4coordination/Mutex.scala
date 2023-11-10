package com.rockthejvm.part4coordination

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

  def create: IO[Mutex] = Ref[IO].of(unlocked).map { refState =>
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
      // for {
      //   state <- refState.get
      //   result <- state match {
      //     case State(false, queue) => IO((State(true, queue), Option.empty[Signal]))
      //     case State(true, queue) =>
      //       Deferred[IO, Unit].map(signal => (State(true, queue :+ signal), Option(signal)))
      //   }
      //   (newState, optionalSignal) = result
      //   _ <- optionalSignal match {
      //     case None         => IO.unit
      //     case Some(signal) => signal.get
      //   }
      //   _ <- refState.set(newState)
      // } yield ()

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
        // for {
        //   state <- refState.get
        //   result = state match {
        //     case state @ State(false, queue)         => (state, Option.empty[Signal])
        //     case State(true, queue) if queue.isEmpty => (State(false, queue), Option.empty[Signal])
        //     case State(true, queue) =>
        //       val (signal, newQueue) = queue.dequeue
        //       (State(true, newQueue), Option(signal))
        //   }
        //   (newState, optionalSignal) = result
        //   _ <- optionalSignal match {
        //     case None         => IO.unit
        //     case Some(signal) => signal.complete(())
        //   }
        //   _ <- refState.set(newState)
        // } yield ()
    }
  }
}

object MutexPlayground extends IOApp.Simple {

  def criticalTask(): IO[Int] = IO.sleep(1.second) >> IO(Random.nextInt(100))

  def createNonLockingTask(id: Int, mutex: Mutex): IO[Int] = for {
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
    results <- (1 to 10).toList.parTraverse(id => createNonLockingTask(id, mutex))
  } yield results
  // only one task can proceed at a task

  override def run: IO[Unit] = demoNonLockingTask().debugs.void
}
