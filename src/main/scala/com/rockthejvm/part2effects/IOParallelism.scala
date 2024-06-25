package com.rockthejvm.part2effects

import cats.Parallel
import cats.effect.IO
import cats.effect.IOApp

object IOParallelism extends IOApp.Simple {

  // IOs are usually sequential
  val aniIO    = IO(s"[${Thread.currentThread().getName} Ani]")
  val kamranIO = IO(s"[${Thread.currentThread().getName} Kamran]")

  val composedIO = for {
    ani    <- aniIO
    kamran <- kamranIO
  } yield s"$ani and $kamran love Rock the JVM"

  // debug extension methods
  import com.rockthejvm.utils.*
  import cats.syntax.apply.*

  val meaningOfLife: IO[Int] = IO.delay(42)
  val favLang: IO[String]    = IO.delay("Scala")
  val goalInLife = (meaningOfLife.debugs, favLang.debugs).mapN((num, string) => s"my goal in life is $num and $string")

  // parallelism on IOs
  // convert a sequential IO to parallel IO
  val parIO1: IO.Par[Int]    = Parallel[IO].parallel(meaningOfLife.debugs)
  val parIO2: IO.Par[String] = Parallel[IO].parallel(favLang.debugs)

  import cats.effect.implicits.*
  val goalInLifeParallel: IO.Par[String] =
    (parIO1, parIO2).mapN((num, string) => s"my goal in life is $num and $string")
  // turn back to sequential
  val goalInLife_v2: IO[String] = Parallel[IO].sequential(goalInLifeParallel)

  // shorthand:
  import cats.syntax.parallel.*
  val goalInLife_v3: IO[String] =
    (meaningOfLife.debugs, favLang.debugs).parMapN((num, string) => s"my goal in life is $num and $string")

  // regarding failure:
  val aFailure: IO[String] = IO.raiseError(new RuntimeException("I can't do this!"))
  // compose a success + failure
  val parallelWithFailure = (meaningOfLife.debugs, aFailure.debugs).parMapN(_ + _)
  // compose failure + failure
  val anotherFailure: IO[String] = IO.raiseError(new RuntimeException("Second failure"))
  val twoFailures: IO[String]    = (aFailure.debugs, anotherFailure.debugs).parMapN(_ + _)
  // the first effect to fail gives the failure of the result
  val twoFailuresDelayed: IO[String] = IO(Thread.sleep(1000)) >> (aFailure.debugs, anotherFailure.debugs).parMapN(_ + _)

  override def run: IO[Unit] =
    twoFailuresDelayed.debugs.void
}
