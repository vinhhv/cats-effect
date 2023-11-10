package com.rockthejvm.part4coordination

import cats.effect.{IO, IOApp}
import cats.effect.std.Semaphore
import cats.syntax.parallel.*
import com.rockthejvm.utils.*

import scala.concurrent.duration.*
import scala.util.Random

object Semaphores extends IOApp.Simple {

  val semaphore: IO[Semaphore[IO]] = Semaphore[IO](2) // 2 total permits

  // example: limiting the number of concurrent sessions on a server
  def doWorkWhileLoggedIn(): IO[Int] = IO.sleep(1.second) >> IO(Random.nextInt(100))

  def login(id: Int, sem: Semaphore[IO]): IO[Int] = for {
    _ <- IO(s"[session $id] waiting to log in...")
    _ <- sem.acquire
    // critical section
    _   <- IO(s"[session $id] logged in, working...").debugs
    res <- doWorkWhileLoggedIn()
    _   <- IO(s"[session $id] done: $res, logging out...").debugs
    // end of critical section
    _ <- sem.release
  } yield res

  def demoSemaphore(): IO[Unit] = for {
    sem      <- Semaphore[IO](2)
    user1fib <- login(1, sem).start
    user2fib <- login(2, sem).start
    user3fib <- login(3, sem).start
    _        <- user1fib.join
    _        <- user2fib.join
    _        <- user3fib.join
  } yield ()

  def weightedLogin(id: Int, requiredPermits: Int, sem: Semaphore[IO]): IO[Int] =
    for {
      _ <- IO(s"[session $id] waiting to log in...")
      _ <- sem.acquireN(requiredPermits)
      // critical section
      _   <- IO(s"[session $id] logged in, working...").debugs
      res <- doWorkWhileLoggedIn()
      _   <- IO(s"[session $id] done: $res, logging out...").debugs
      // end of critical section
      _ <- sem.releaseN(requiredPermits)
    } yield res

  def demoWeightedSemaphore(): IO[Unit] = for {
    sem      <- Semaphore[IO](2)
    user1fib <- weightedLogin(1, 1, sem).start
    user2fib <- weightedLogin(2, 2, sem).start
    user3fib <- weightedLogin(3, 3, sem).start
    _        <- user1fib.join
    _        <- user2fib.join
    _        <- user3fib.join
  } yield ()

  /**
   * Exercise
   * 1. find out if there's something wrong with this code: creates a mutex per id, we only need one mutex
   * 2. why
   * 3. fix it
   */
  // Semaphore with 1 permit == mutex
  val mutex = Semaphore[IO](1)
  val users: IO[List[Int]] = mutex.flatMap { sem =>
    (1 to 10).toList.parTraverse { id =>
      for {
        _ <- IO(s"[session $id] waiting to log in...")
        _ <- sem.acquire
        // critical section
        _   <- IO(s"[session $id] logged in, working...").debugs
        res <- doWorkWhileLoggedIn()
        _   <- IO(s"[session $id] done: $res, logging out...").debugs
        // end of critical section
        _ <- sem.release
      } yield res
    }
  }

  override def run: IO[Unit] = users.debugs.void
}
