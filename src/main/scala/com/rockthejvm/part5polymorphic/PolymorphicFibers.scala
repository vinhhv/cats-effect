package com.rockthejvm.part5polymorphic

import cats.effect.{Fiber, IO, IOApp, MonadCancel, Outcome, OutcomeIO, Spawn}
import com.rockthejvm.utils.*

import scala.concurrent.duration.*

object PolymorphicFibers extends IOApp.Simple {

  // Spawn = create fibers for any effect

  trait MyGenSpawn[F[_], E] extends MonadCancel[F, E] {
    def start[A](fa: F[A]): F[Fiber[F, Throwable, A]] // create a fiber
    def never[A]: F[A]                                // a forever-suspending effect
    def cede: F[Unit]                                 // a "yield" effect

    def racePair[A, B](fa: F[A], fb: F[B]): F[Either[
      (Outcome[F, E, A], Fiber[F, E, B]),
      (Fiber[F, E, A], Outcome[F, E, B])
    ]]
  }

  trait MySpawn[F[_]] extends MyGenSpawn[F, Throwable]

  val mol                                  = IO(42)
  val fiber: IO[Fiber[IO, Throwable, Int]] = mol.start

  // pure, map/flatMap, raiseError, uncancelable, start

  val spawnIO = Spawn[IO] // fetch the given/implicit Spawn[IO]

  def ioOnSomeThread[A](ioa: IO[A]): IO[OutcomeIO[A]] =
    for {
      fib    <- spawnIO.start(ioa)
      result <- fib.join
    } yield result

  import cats.syntax.flatMap.* // flatMap
  import cats.syntax.functor.* // map

  // generalize
  import cats.effect.syntax.spawn.* // start extension methods
  def effectOnSomeThread[F[_]: Spawn, A](fa: F[A]): F[Outcome[F, Throwable, A]] =
    for {
      fib    <- fa.start
      result <- fib.join
    } yield result

  val molOnFiber    = ioOnSomeThread(mol)
  val molOnFiber_v2 = effectOnSomeThread(mol)

  /**
   * Exercise - generalize the following code
   */
  def simpleRace[F[_], A, B](fa: F[A], fb: F[B])(using spawn: Spawn[F]): F[Either[A, B]] =
    spawn.racePair(fa, fb).flatMap {
      case Left((outA, fibB)) =>
        outA match
          case Outcome.Succeeded(fa) => fibB.cancel >> fa.map(a => Left(a))
          case Outcome.Errored(e)    => fibB.cancel >> spawn.raiseError(e)
          case Outcome.Canceled() =>
            fibB.join.flatMap {
              case Outcome.Succeeded(fb) => fb.map(b => Right(b))
              case Outcome.Errored(e)    => spawn.raiseError(e)
              case Outcome.Canceled()    => spawn.raiseError(new RuntimeException("Both computations got canceled"))
            }
      case Right((fibA, outB)) =>
        outB match
          case Outcome.Succeeded(fb) => fibA.cancel >> fb.map(b => Right(b))
          case Outcome.Errored(e)    => fibA.cancel >> spawn.raiseError(e)
          case Outcome.Canceled() =>
            fibA.join.flatMap {
              case Outcome.Succeeded(fa) => fa.map(a => Left(a))
              case Outcome.Errored(e)    => spawn.raiseError(e)
              case Outcome.Canceled()    => spawn.raiseError(new RuntimeException("Both computations got canceled"))
            }
    }

  override def run: IO[Unit] =
    simpleRace[IO, String, String](IO.sleep(1.second) >> IO("left").debugs, IO.sleep(500.millis) >> IO("right").debugs).debugs.void
}
