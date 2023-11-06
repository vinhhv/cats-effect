package com.rockthejvm.part2effects

import cats.effect.IO

import scala.annotation.tailrec
import scala.io.StdIn

object IOIntroduction {

  // IO
  val ourFirstIO: IO[Int] = IO.pure(42) // arg that should not have side effects
  val aDelayedIO: IO[Int] = IO.delay {
    println("I'm producing an integer")
    54
  }

  val shouldNotDoThis: IO[Int] = IO.pure {
    println("I'm producing an integer")
    54
  }

  val aDelayedIO_v2: IO[Int] = IO {
    println("I'm producing an integer")
    54
  }

  // map, flatMap
  val improvedMeaningOfLife = ourFirstIO.map(_ * 2)
  val printedMeaningOfLife  = ourFirstIO.flatMap(mol => IO.delay(println(mol)))

  def smalLProgram(): IO[Unit] = for {
    line1 <- IO(StdIn.readLine())
    line2 <- IO(StdIn.readLine())
    _     <- IO.delay(println(line1 + line2))
  } yield ()

  // mapN - combine IO effects as tuples
  import cats.syntax.apply.*
  val combinedMeaningOfLife: IO[Int] = (ourFirstIO, improvedMeaningOfLife).mapN(_ + _)
  def smallProgram_v2(): IO[Unit] =
    (IO(StdIn.readLine()), IO(StdIn.readLine())).mapN(_ + _).map(println)

  /**
   * Exercises
   */

  // 1 - sequence two IOs and take the result of the last one
  // hint - use flatMap
  def sequenceTakeLast[A, B](ioa: IO[A], iob: IO[B]): IO[B] =
    // for {
    //  _ <- ioa
    //  b <- iob
    // } yield b
    // ioa.flatMap(_ => iob)
    // ioa *> iob // andThen
    ioa >> iob // andThen with by-name call

  // 2 - sequence two IOs and take the result of the FIRST one
  // hint - use flatMap
  def sequenceTakeFirst[A, B](ioa: IO[A], iob: IO[B]): IO[A] =
    // for {
    //  a <- ioa
    //  _ <- iob
    // } yield a
    ioa <* iob

  // 3 - repeat an IO effect forever
  // hint - use flatMap + recursion
  def forever[A](io: IO[A]): IO[A] =
    // for {
    //   a <- forever(io)
    // } yield a
    // io.flatMap(_ => forever(io)) // same
    // io >> forever(io) // same
    // io *> forever(io) // same
    io.foreverM // with tail recursion

      // 4 - convert an IO to a different type
      // hint: use map
  def convert[A, B](ioa: IO[A], value: B): IO[B] =
    // ioa.map(_ => value)
    ioa.as(value) // same

  // 5 - discard value inside an IO, just return Unit
  def asUnit[A](ioa: IO[A]): IO[Unit] =
    // convert(ioa, ())
    // ioa.as(()) // discourage - don't use this
    ioa.void // same - encouraged

      // 6 - fix stack recursion
  def sum(n: Int): Int =
    if (n <= 0) 0
    else n + sum(n - 1)

  def sumIO(n: Int): IO[Int] =
    if (n <= 0) IO.pure(0)
    else IO(n).flatMap(n0 => sumIO(n - 1).map(n1 => n0 + n1))

  // 7 (hard) - write a fibonacci IO that does NOT crash on recursion
  // hint: use recursion, ignore exponential complexity, use flatMap heavily
  def fibonacci(n: Int): IO[BigInt] =
    if (n < 2) IO(1)
    else
      for {
        last <- IO.defer(
          fibonacci(n - 1)
        ) // same as .delay(...).flatten, need to do this because first IO is not within a flatMap call (not stack-safe)
        prev <- fibonacci(n - 2)
      } yield last + prev

  def main(args: Array[String]): Unit = {
    import cats.effect.unsafe.implicits.global // "platform"
    // "end of the world"
    // println(aDelayedIO.unsafeRunSync())
    // println(smallProgram_v2().unsafeRunSync())
    // forever(IO(println("forever!"))).unsafeRunSync()
    println(sumIO(20000).unsafeRunSync())
    println(fibonacci(40).unsafeRunSync())
  }
}
