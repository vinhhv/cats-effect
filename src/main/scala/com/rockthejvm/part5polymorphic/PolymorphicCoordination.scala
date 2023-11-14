package com.rockthejvm.part5polymorphic

import cats.effect.{Concurrent, Deferred, Fiber, IO, IOApp, Outcome, Ref, Spawn}
import com.rockthejvm.utils.general.*

import scala.concurrent.duration.*

object PolymorphicCoordination extends IOApp.Simple {

  // Concurrent - Ref + Deferred for ANY effect type
  trait MyConcurrent[F[_]] extends Spawn[F] {
    def ref[A](a: A): F[Ref[F, A]]
    def deferred[A]: F[Deferred[F, A]]
  }

  val concurrentIO = Concurrent[IO]    // given instance of Concurrent[IO]
  val aDeferred    = Deferred[IO, Int] // given Concurrent[IO] in scope
  val aDeferred_v2 = concurrentIO.deferred[Int]
  val aRef         = concurrentIO.ref(42)

  // capabilities: pure, map/flatMap, raiseError, uncancelable, start (fibers), + ref/deferred

  import cats.effect.syntax.spawn.*
  import cats.syntax.flatMap.*
  import cats.syntax.functor.*

  def polymorphicEggBoiler[F[_]](using concurrent: Concurrent[F]): F[Unit] = {
    def eggReadyNotification(signal: Deferred[F, Unit]) = for {
      _ <- concurrent.pure("Egg boiling on some other fiber, waiting...").debugs
      _ <- signal.get
      _ <- concurrent.pure("EGG READY!").debugs
    } yield ()

    def tickingClock(counter: Ref[F, Int], signal: Deferred[F, Unit]): F[Unit] = for {
      _     <- unsafeSleep[F, Throwable](1.second)
      count <- counter.updateAndGet(_ + 1)
      _     <- concurrent.pure(count).debugs
      _     <- if (count >= 10) signal.complete(()).void else tickingClock(counter, signal)
    } yield ()

    for {
      counter         <- concurrent.ref(0)
      signal          <- concurrent.deferred[Unit]
      notificationFib <- eggReadyNotification(signal).start
      clock           <- tickingClock(counter, signal).start
      _               <- notificationFib.join
      _               <- clock.join
    } yield ()
  }

  /**
   * Exercises:
   * 1. Generalize racePair
   * 2. Generalize the Mutex concurrency primitive for any F
   */
  type RaceResult[F[_], A, B] = Either[
    (Outcome[F, Throwable, A], Fiber[F, Throwable, B]), // (winner result, loser fiber)
    (Fiber[F, Throwable, A], Outcome[F, Throwable, B])  // (loser fiber, winner result)
  ]

  type EitherOutcome[F[_], A, B] = Either[Outcome[F, Throwable, A], Outcome[F, Throwable, B]]

  import cats.effect.syntax.monadCancel.*
  import cats.effect.syntax.spawn.*

  def ourRacePair[F[_], A, B](fa: F[A], fb: F[B])(using concurrent: Concurrent[F]): F[RaceResult[F, A, B]] =
    concurrent.uncancelable { poll =>
      for {
        signal <- concurrent.deferred[EitherOutcome[F, A, B]]
        fiba   <- fa.guaranteeCase(outcomeA => signal.complete(Left(outcomeA)).void).start
        fibb   <- fb.guaranteeCase(outcomeB => signal.complete(Right(outcomeB)).void).start
        result <- poll(signal.get).onCancel { // blocking call - should be cancellable
          for {
            cancelFibA <- fiba.cancel.start
            cancelFibB <- fibb.cancel.start
            _          <- cancelFibA.join
            _          <- cancelFibB.join
          } yield ()
        }
      } yield result match {
        case Left(outcomeA)  => Left((outcomeA, fibb))
        case Right(outcomeB) => Right((fiba, outcomeB))
      }
    }

  override def run: IO[Unit] = polymorphicEggBoiler[IO]
}
