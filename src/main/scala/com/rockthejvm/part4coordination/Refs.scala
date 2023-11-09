package com.rockthejvm.part4coordination

import cats.effect.{IO, IOApp, Ref}
import cats.syntax.parallel.*
import com.rockthejvm.utils.*

import scala.concurrent.duration.*

object Refs extends IOApp.Simple {

  // ref = purely functional atomic reference
  val atomicMol: IO[Ref[IO, Int]]    = Ref[IO].of(42)
  val atomicMol_v2: IO[Ref[IO, Int]] = IO.ref(42)

  // modifying is an effect
  val increasedMol: IO[Unit] = atomicMol.flatMap { ref =>
    ref.set(43) // thread-safe
  }

  // obtain a value
  val mol = atomicMol.flatMap { ref =>
    ref.get // thread-safe
  }

  val gsMol: IO[Int] = atomicMol.flatMap { ref =>
    ref.getAndSet(43) // gets old value, sets the new one
  }

  // updating with a function
  val fMol: IO[Unit] = atomicMol.flatMap { ref =>
    ref.update(_ * 10)
  }

  val updatedMol: IO[Int] = atomicMol.flatMap { ref =>
    ref.updateAndGet(_ * 10) // gets the new value
    // getAndUpdate to get the OLD value
  }

  // modifying with a function returning a different type
  val modifiedMol: IO[String] = atomicMol.flatMap { ref =>
    ref.modify(value => (value * 10, s"my current value is $value"))
  }

  // why: concurrent + thread-safe reads/writes over shared values, in a purely functional way

  def demoConcurrentWorkImpure(): IO[Unit] = {
    import cats.syntax.parallel.*
    var count = 0

    def task(workload: String): IO[Unit] = {
      val wordCount = workload.split(" ").length
      for {
        _        <- IO(s"Counting words for '$workload': $wordCount").debugs
        newCount <- IO(count + wordCount)
        _        <- IO(s"New total: $newCount").debugs
        _        <- IO(count += wordCount)
      } yield ()
    }

    List("I Love Cats Effect", "This ref thing is useless", "Daniel writes a lot of code")
      .map(task)
      .parSequence
      .void
  }

  /*
    Drawbacks:
    - hard to read/debug
    - mix pure/impure code
    - NOT THREAD SAFE
   */
  def demoConcurrentWorkPure(): IO[Int] = {
    import cats.syntax.parallel.*
    def task(workload: String, total: Ref[IO, Int]): IO[Unit] = {
      val wordCount = workload.split(" ").length

      for {
        _        <- IO(s"Counting words for '$workload': $wordCount").debugs
        newCount <- total.updateAndGet(_ + wordCount)
        _        <- IO(s"New total: $newCount").debugs
      } yield ()
    }

    for {
      ref <- Ref[IO].of(0)
      _ <- List("I Love Cats Effect", "This ref thing is useless", "Daniel writes a lot of code")
        .map(string => task(string, ref))
        .parSequence
      finalCount <- ref.get
    } yield finalCount
  }

  /**
   * Exercise
   */
  def tickingClockImpure(): IO[Unit] = {
    var ticks: Long = 0L
    def tickingClock: IO[Unit] = for {
      _ <- IO.sleep(1.second)
      _ <- IO(System.currentTimeMillis()).debugs
      _ <- IO(ticks += 1) // not thread safe
      _ <- tickingClock
    } yield ()

    def printTicks: IO[Unit] = for {
      _ <- IO.sleep(5.seconds)
      _ <- IO(s"TICKS: $ticks").debugs
      _ <- printTicks
    } yield ()

    for {
      _ <- (tickingClock, printTicks).parTupled
    } yield ()
  }

  def tickingClockPure(): IO[Unit] = {
    def tickingClock(ticks: Ref[IO, Int]): IO[Unit] = for {
      _ <- IO.sleep(1.second)
      _ <- IO(System.currentTimeMillis()).debugs
      _ <- ticks.update(_ + 1) // thread safe effect
      _ <- tickingClock(ticks)
    } yield ()

    def printTicks(ticks: Ref[IO, Int]): IO[Unit] = for {
      _ <- IO.sleep(5.seconds)
      t <- ticks.get
      _ <- IO(s"TICKS: $t").debugs
      _ <- printTicks(ticks)
    } yield ()

    for {
      ref <- Ref[IO].of(0)
      _   <- (tickingClock(ref), printTicks(ref)).parTupled
    } yield ()
  }

  override def run: IO[Unit] = tickingClockPure().debugs.void
}
