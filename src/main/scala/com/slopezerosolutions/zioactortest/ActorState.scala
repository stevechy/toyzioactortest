package com.slopezerosolutions.zioactortest

import zio.*

object ActorState {
  abstract sealed class Phase
  case class Running() extends Phase
  case class Suspended() extends Phase
  case class Stopped() extends Phase

}
case class ActorState[T](phase: ActorState.Phase,
                         parent: Option[String],
                         children: Set[String],
                         actor: Actor[T],
                         actorTemplate: ActorTemplate[T],
                         fiber: Fiber[Throwable, Any])
