package com.slopezerosolutions.zioactortest

import zio.Task

trait ActorService {
  def startActor[T](template: ActorTemplate[T]): Task[MessageDestination[T]]
}
