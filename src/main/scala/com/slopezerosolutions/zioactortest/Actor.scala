package com.slopezerosolutions.zioactortest

class Actor[T](val actorId: String, val inbox: zio.Queue[T]) {

}
