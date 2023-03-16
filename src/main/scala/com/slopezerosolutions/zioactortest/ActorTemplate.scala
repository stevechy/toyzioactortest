package com.slopezerosolutions.zioactortest

import zio.Task

object ActorTemplate {
  def handler[T](handle: T => Task[Boolean]): ActorTemplate[T] = {
    HandlerActorTemplate((actorCreator: ActorService, message: T) => handle(message))
  }

  def handler[T](handle: (ActorService,T) => Task[Boolean]): ActorTemplate[T] = {
    HandlerActorTemplate(handle)
  }
}

abstract sealed class ActorTemplate[T]
case class HandlerActorTemplate[T](handler: (ActorService,T) => Task[Boolean]) extends ActorTemplate[T]
