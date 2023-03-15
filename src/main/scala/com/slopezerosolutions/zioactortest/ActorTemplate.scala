package com.slopezerosolutions.zioactortest

import zio.Task

abstract sealed class ActorTemplate[T]
case class HandlerActorTemplate[T](handler: T => Task[Boolean]) extends ActorTemplate[T]
