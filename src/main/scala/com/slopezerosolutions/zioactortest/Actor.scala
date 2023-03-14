package com.slopezerosolutions.zioactortest

class Actor[T](val inbox: zio.Queue[T]) {

}
