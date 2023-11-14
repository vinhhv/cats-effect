package com.rockthejvm.part5polymorphic

import cats.effect.{Concurrent, IO, IOApp, Temporal}
import com.rockthejvm.utils.general.*

import scala.concurrent.duration.*

object PolymorphicTemporalSuspension extends IOApp.Simple {

  // Temporal - time-blocking effects
  trait MyTemporal[F[_]] extends Concurrent[F] {
    def sleep(time: FiniteDuration): F[Unit] // semantically blocks this fiber for a specified time
  }

  // abilities: pure, map/flatMap, raiseError, uncancelable, start, ref/deferred, +sleep
  val temporalIO     = Temporal[IO] // given Temporal[IO] in scope
  val chainOfEffects = IO("Loading...").debugs >> IO.sleep(1.second) >> IO("Game ready!").debugs
  val chainOfEffects_v2 =
    temporalIO.pure("Loading...").debugs >> temporalIO.sleep(1.second) >> temporalIO.pure("Game ready!").debugs

  /**
   * Exercise: generalize the following code
   */
  import cats.syntax.flatMap.*

  def timeout[F[_], A](fa: F[A], duration: FiniteDuration)(using temporal: Temporal[F]): F[A] =
    temporal.race(fa, temporal.sleep(duration)).flatMap {
      case Left(a)  => temporal.pure(a)
      case Right(_) => temporal.raiseError(new RuntimeException("Timed out"))
    }

  override def run: IO[Unit] = chainOfEffects_v2.void
}
