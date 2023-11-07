package com.rockthejvm.part3concurrency

import cats.effect.kernel.Outcome
import cats.effect.{FiberIO, IO, IOApp, OutcomeIO}
import com.rockthejvm.utils.*

import scala.concurrent.duration.*

object RacingIOs extends IOApp.Simple {

  def runWithSleep[A](value: A, duration: FiniteDuration): IO[A] =
    (
      IO(s"starting computation").debugs >>
        IO.sleep(duration) >>
        IO(s"computation: done") >>
        IO(value)
    ).onCancel(IO(s"Computation CANCELED for $value").debugs.void)

  def testRace() = {
    val meaningOfLife                  = runWithSleep(42, 1.second)
    val favLang                        = runWithSleep("Scala", 2.seconds)
    val first: IO[Either[Int, String]] = IO.race(meaningOfLife, favLang)
    /*
    - both IOs run on separate fibers
    - the first one to finish will complete the result
    - the loser will be canceled
     */
    first.flatMap {
      case Left(mol)   => IO(s"Meaning of life won: $mol")
      case Right(lang) => IO(s"Fav language won: $lang")
    }
  }

  def testRacePair() = {
    val meaningOfLife = runWithSleep(42, 1.second)
    val favLang       = runWithSleep("Scala", 2.seconds)
    val raceResult: IO[Either[
      (OutcomeIO[Int], FiberIO[String]), // (winner result, loser fiber)
      (FiberIO[Int], OutcomeIO[String])  // (loser fiber, winner result)
    ]] = IO.racePair(meaningOfLife, favLang)

    raceResult.flatMap {
      case Left((outMol, fibLang))  => fibLang.cancel >> IO("MOL won").debugs >> IO(outMol).debugs
      case Right((fibMol, outLang)) => fibMol.cancel >> IO("Language won").debugs >> IO(outLang).debugs
    }
  }

  /**
   * Exercises:
   * 1 - implement a timeout pattern with race
   * 2 - a method to return a LOSING effect from a race (hint: use racePair)
   * 3 - implement race in terms of racePair
   */

  // 1
  def timeout[A](io: IO[A], duration: FiniteDuration): IO[A] =
    IO.race(io, IO.sleep(duration)).flatMap {
      case Left(a)  => IO(a)
      case Right(_) => IO.raiseError(new RuntimeException("Timed out"))
    }

  // 2
  def unrace[A, B](ioa: IO[A], iob: IO[B]): IO[Either[A, B]] =
    IO.racePair(ioa, iob).flatMap {
      case Left((_, fibB)) =>
        fibB.join.flatMap {
          case Outcome.Succeeded(fb) => fb.map(value => Right(value))
          case Outcome.Errored(e)    => IO.raiseError(e)
          case Outcome.Canceled()    => IO.raiseError(new RuntimeException("iob got canceled"))
        }
      case Right((fibA, _)) =>
        fibA.join.flatMap {
          case Outcome.Succeeded(fa) => fa.map(value => Left(value))
          case Outcome.Errored(e)    => IO.raiseError(e)
          case Outcome.Canceled()    => IO.raiseError(new RuntimeException("ioa got canceled"))
        }
    }

  // 3
  def simpleRace[A, B](ioa: IO[A], iob: IO[B]): IO[Either[A, B]] =
    IO.racePair(ioa, iob).flatMap {
      case Left((outA, fibB)) =>
        outA match
          case Outcome.Succeeded(fa) => fibB.cancel >> fa.map(a => Left(a))
          case Outcome.Errored(e)    => fibB.cancel >> IO.raiseError(e)
          case Outcome.Canceled() =>
            fibB.join.flatMap {
              case Outcome.Succeeded(fb) => fb.map(b => Right(b))
              case Outcome.Errored(e)    => IO.raiseError(e)
              case Outcome.Canceled()    => IO.raiseError(new RuntimeException("Both computations got canceled"))
            }
      case Right((fibA, outB)) =>
        outB match
          case Outcome.Succeeded(fb) => fibA.cancel >> fb.map(b => Right(b))
          case Outcome.Errored(e) => fibA.cancel >> IO.raiseError(e)
          case Outcome.Canceled() =>
            fibA.join.flatMap {
              case Outcome.Succeeded(fa) => fa.map(a => Left(a))
              case Outcome.Errored(e) => IO.raiseError(e)
              case Outcome.Canceled() => IO.raiseError(new RuntimeException("Both computations got canceled"))
            }
    }

  override def run: IO[Unit] =
    simpleRace(IO.sleep(2.seconds) >> IO("left").debugs, IO.sleep(1.seconds) >> IO("right").debugs).debugs.void
}
