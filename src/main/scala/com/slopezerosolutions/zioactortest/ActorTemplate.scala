package com.slopezerosolutions.zioactortest

import zio.Task

object ActorTemplate {
  def handler[T](handle: T => Task[Boolean]): ActorTemplate[T] = {
    HandlerActorTemplate((actorService: ActorService, message: T) => handle(message))
  }

  def handler[T](handle: (ActorService,T) => Task[Boolean]): ActorTemplate[T] = {
    HandlerActorTemplate(handle)
  }

  def stateful[M,State](_initialStateSupplier: () => State,
                        _handler: (ActorService,M, State) => Task[StatefulActor.Result[State]]): ActorTemplate[M] = {
    new StatefulActorTemplate[M] {
      type S = State
      val initialStateSupplier: () => S = _initialStateSupplier
      val handler: (ActorService,M, S) => Task[StatefulActor.Result[S]] = _handler
    }
  }
}

abstract sealed class ActorTemplate[T]
case class HandlerActorTemplate[T](handler: (ActorService,T) => Task[Boolean]) extends ActorTemplate[T]
abstract class StatefulActorTemplate[M] extends ActorTemplate[M] {
  type S

  val initialStateSupplier: () => S
  val handler: (ActorService,M, S) => Task[StatefulActor.Result[S]]
}
