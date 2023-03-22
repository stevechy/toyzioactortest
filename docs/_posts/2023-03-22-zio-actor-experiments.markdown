---
layout: post
title:  "Actor implementation experiments with ZIO"
date:   2023-03-22 17:26:12 -0500
categories: zio actors
---

I've been going through the excellent ZIO course at [RockTheJVM](https://rockthejvm.com/courses/) and I wanted to experiment with what I've learned.

A lot of the tools provided by ZIO make sense for actors so I started working on a toy implementation.
I got interested in the idea of a single-process actor library after reading about  [Shardcake](https://github.com/devsisters/shardcake).
It's easier for me to think about actors when there's a clear boundary between in-server and cross-server communication so I focused on that.

I could have started with [ZIO Actors](https://github.com/zio/zio-actors) but it was fun to try out different API ideas and see how they worked.
For enterprise use cases that benefit from transparent actor distribution [Akka](https://akka.io/) is the defacto solution on the JVM but I was mostly interested in trying out ideas and not looking into building a full application.

My main takeaway from this experience is that it's worth exploring single-process actor libraries.
Working through the implementation let me hash out some of my ideas about actors and generated a few more.
The source code is available in this [Github repo](https://github.com/stevechy/toyzioactortest).

Warnings:
- I have not used the actor model or Scala in production before. One of the reasons for going through this was to get some better intuitions about the actor model.
- This post gets disjointed at some points since I was working through a lot of ideas and there wasn't really a common thread to tie them together.
- The default Jekyll theme makes h1 24px and h2 32px and that's confusing

Still here? Well then let's go through the main parts of the implementation and the ideas I worked through.

# Request-response messages

[ZIO Actors](https://github.com/zio/zio-actors#introduction) has a nice description of the actor model.
I'm mostly interested in two pieces:
- Actors process one message at a time
- Actors have supervising parent actors that handle faults by restarting supervised child actors.

A [ZIO Queue](https://zio.dev/reference/concurrency/queue) is perfect for processing one message at a time.
Before processing the messages I wanted an easy way to send a message to an actor and get a response back.

Since we're sticking to a single-process we can use a [ZIO Promise](https://zio.dev/reference/concurrency/promise).
With multiple processes we would need a way of matching up a remote reply back to the promise.

<style type="text/css">
pre { background-color: #2b2b2b;}
.s0 { color: #cc7832;}
.s1 { color: #a9b7c6;}
.s2 { color: #6a8759;}
.s3 { color: #6897bb;}
.s4 { color: #808080;}
</style>

<pre><span class="s0">package </span><span class="s1">com.slopezerosolutions.zioactortest</span>

<span class="s0">import </span><span class="s1">zio._</span>
<span class="s0">import </span><span class="s1">zio.test._</span>

<span class="s0">class </span><span class="s1">ActorSystemSpec </span><span class="s0">extends </span><span class="s1">zio.test.junit.JUnitRunnableSpec {</span>

  <span class="s0">def </span><span class="s1">spec: Spec[Any</span><span class="s0">, </span><span class="s1">Throwable] = suite(</span><span class="s2">&quot;ActorSystem tests&quot;</span><span class="s1">)(</span>
    <span class="s1">test(</span><span class="s2">&quot;can send a message&quot;</span><span class="s1">) {</span>
      <span class="s0">val </span><span class="s1">actorSystem = </span><span class="s0">new </span><span class="s1">ActorSystem</span>

      <span class="s0">val </span><span class="s1">sendMessageZIO = </span><span class="s0">for</span><span class="s1">{</span>
        <span class="s1">resultPromise &lt;- Promise.make[Throwable</span><span class="s0">, </span><span class="s1">String]</span>
        <span class="s1">destination &lt;- ZIO.succeed(actorSystem.promiseMessageDestination(resultPromise))</span>
        <span class="s1">_ &lt;- actorSystem.send(</span><span class="s2">&quot;Hello world&quot;</span><span class="s0">, </span><span class="s1">destination)</span>
        <span class="s1">result &lt;- resultPromise.await</span>
      <span class="s1">} </span><span class="s0">yield </span><span class="s1">result</span>
      <span class="s1">assertZIO(sendMessageZIO)(Assertion.equalTo(</span><span class="s2">&quot;Hello world&quot;</span><span class="s1">))</span>
    <span class="s1">}</span>
  <span class="s1">)</span>
<span class="s1">}</span>
</pre>

The next issue to handle is if the reply needs to be translated before we can process it.

In a normal request-response call we can wait for the response and translate it there.
In the actor style, the response should come back to the actor's main inbox.

The problem is that the receiver should usually respond in the receiver's format, not in the sender's format.
If a `CashRegisterActor` sends a `PaymentActor` a `Payment` message then:
1. `PaymentActor` can respond with a `CashRegisterActor` format message, but this makes `PaymentActor` harder to reuse
2. `PaymentActor` can respond with a `PaymentActor` format message, but this makes `CashRegisterActor`'s message format harder to read.

Akka recommends the [Adapted Response Pattern](https://doc.akka.io/docs/akka/current/typed/interaction-patterns.html#adapted-response) to solve this issue. 
The actor registers message adapters when it's initialized and uses these adapter addresses as reply references.

In the single-process case it's easier to generate adapters on demand. 
For example, if we want to retry a payment, we could include an internal request id in the adapter so the actor can distinguish between multiple payment responses.

<pre>
    <span class="s1">test(</span><span class="s2">&quot;can send a message to an adapted destination&quot;</span><span class="s1">) {</span>
      <span class="s0">val </span><span class="s1">actorSystem = </span><span class="s0">new </span><span class="s1">ActorSystem</span>

      <span class="s0">val </span><span class="s1">sendMessageZIO = </span><span class="s0">for </span><span class="s1">{</span>
        <span class="s1">resultPromise &lt;- Promise.make[Throwable</span><span class="s0">, </span><span class="s1">Int]</span>
        <span class="s1">destination &lt;- ZIO.succeed(actorSystem.promiseMessageDestination(resultPromise))</span>
        <span class="s1">adapterDestination &lt;- ZIO.succeed(actorSystem.adaptedMessageDestination(</span>
          <span class="s1">(stringValue:String) =&gt; stringValue.length</span><span class="s0">,</span>
          <span class="s1">destination))</span>
        <span class="s1">_ &lt;- actorSystem.send(</span><span class="s2">&quot;Hello world&quot;</span><span class="s0">, </span><span class="s1">adapterDestination)</span>
        <span class="s1">result &lt;- resultPromise.await</span>
      <span class="s1">} </span><span class="s0">yield </span><span class="s1">result</span>
      <span class="s1">assertZIO(sendMessageZIO)(Assertion.equalTo(</span><span class="s3">11</span><span class="s1">))</span>
    <span class="s1">}</span>
</pre>

In the initial implementation I implemented a `send` message method in `ActorSystem`.
This ended up cluttering the code so I changed it later.

<pre><span class="s0">package </span><span class="s1">com.slopezerosolutions.zioactortest</span>

<span class="s0">import </span><span class="s1">zio._</span>
<span class="s0">class </span><span class="s1">ActorSystem {</span>

  <span class="s0">def </span><span class="s1">promiseMessageDestination[T](promise: Promise[Throwable</span><span class="s0">, </span><span class="s1">T]): MessageDestination[T] = {</span>
    <span class="s1">PromiseMessageDestination(promise)</span>
  <span class="s1">}</span>

  <span class="s0">def </span><span class="s1">adaptedMessageDestination[I</span><span class="s0">,</span><span class="s1">O](adapter: I =&gt; O</span><span class="s0">, </span><span class="s1">messageDestination: MessageDestination[O]): MessageDestination[I] = {</span>
    <span class="s1">AdaptedMessageDestination(adapter</span><span class="s0">, </span><span class="s1">messageDestination)</span>
  <span class="s1">}</span>

  <span class="s0">def </span><span class="s1">send[T](message: T</span><span class="s0">, </span><span class="s1">messageDestination: MessageDestination[T]): UIO[Boolean] = {</span>
    <span class="s1">messageDestination </span><span class="s0">match </span><span class="s1">{</span>
      <span class="s0">case </span><span class="s1">PromiseMessageDestination(promise) =&gt; promise.succeed(message)</span>
      <span class="s0">case </span><span class="s1">AdaptedMessageDestination(adapter</span><span class="s0">, </span><span class="s1">messageDestination) =&gt; {</span>
        <span class="s0">val </span><span class="s1">adaptedMessage = adapter.apply(message)</span>
        <span class="s1">send(adaptedMessage</span><span class="s0">, </span><span class="s1">messageDestination)</span>
      <span class="s1">}</span>
    <span class="s1">}</span>
  <span class="s1">}</span>
<span class="s1">}</span>
</pre>

# Creating actors and talking to them

To create actors I started with the simplest case, a lambda that takes in one message. 
Because this actor only knows about the message it has received, it can only reply to the sender.

<pre>
<span class="s0">case class </span><span class="s1">PingMessage(replyTo: MessageDestination[String])</span>

<span class="s1">test(</span><span class="s2">&quot;Can send a message to an actor and receive a reply&quot;</span><span class="s1">) {</span>
  <span class="s0">val </span><span class="s1">sendMessageZIO = </span><span class="s0">for </span><span class="s1">{</span>
    <span class="s1">actorSystem &lt;- ActorSystem.initialize</span>
    <span class="s1">actorMessageDestination &lt;- actorSystem.startActor((pingMessage: PingMessage) =&gt; </span><span class="s0">for </span><span class="s1">{</span>
      <span class="s1">result &lt;- actorSystem.send(</span><span class="s2">&quot;Pong!&quot;</span><span class="s0">, </span><span class="s1">pingMessage.replyTo)</span>
    <span class="s1">} </span><span class="s0">yield </span><span class="s1">result)</span>
    <span class="s1">resultPromise &lt;- Promise.make[Throwable</span><span class="s0">, </span><span class="s1">String]</span>
    <span class="s1">destination &lt;- ZIO.succeed(actorSystem.promiseMessageDestination(resultPromise))</span>
    <span class="s1">result &lt;- actorSystem.send(PingMessage(destination)</span><span class="s0">, </span><span class="s1">actorMessageDestination)</span>
    <span class="s1">promiseResult &lt;- resultPromise.await</span>
  <span class="s1">} </span><span class="s0">yield </span><span class="s1">promiseResult</span>
  <span class="s1">assertZIO(sendMessageZIO)(Assertion.equalTo(</span><span class="s2">&quot;Pong!&quot;</span><span class="s1">))</span>
<span class="s1">}</span>
</pre>

[ZIO Queue](https://zio.dev/reference/concurrency/queue) makes this implementation easy.

<pre>
<span class="s0">def </span><span class="s1">startActor[T](handler: T =&gt; Task[Boolean]): UIO[MessageDestination[T]] = </span><span class="s0">for </span><span class="s1">{</span>
  <span class="s1">actorId &lt;- ZIO.succeed(java.util.UUID.randomUUID().toString)</span>
  <span class="s1">inbox &lt;- zio.Queue.bounded[T](</span><span class="s2">100</span><span class="s1">)</span>
  <span class="s1">actor &lt;- ZIO.succeed(</span><span class="s0">new </span><span class="s1">Actor(inbox))</span>
  <span class="s1">_ &lt;- actorLoop(actor</span><span class="s0">, </span><span class="s1">handler).forkDaemon</span>
  <span class="s1">_ &lt;- STM.atomically {</span>
    <span class="s1">actors.put(actorId</span><span class="s0">, </span><span class="s1">actor.asInstanceOf[Actor[Nothing]])</span>
  <span class="s1">}</span>
<span class="s1">} </span><span class="s0">yield </span><span class="s1">ActorMessageDestination[T](actorId)</span>

<span class="s0">private def </span><span class="s1">actorLoop[T](actor: Actor[T]</span><span class="s0">, </span><span class="s1">handler: T =&gt; Task[Boolean]): Task[Boolean] = {</span>
  <span class="s0">val </span><span class="s1">handleMessage: Boolean =&gt; Task[Boolean] = (state: Boolean) =&gt; </span><span class="s0">for </span><span class="s1">{</span>
    <span class="s1">message &lt;- actor.inbox.take</span>
    <span class="s1">_ &lt;- handler(message)</span>
  <span class="s1">} </span><span class="s0">yield true</span>
  <span class="s0">val </span><span class="s1">function: Boolean =&gt; Boolean = _ != </span><span class="s0">false</span>
  <span class="s0">for </span><span class="s1">{</span>
    <span class="s1">_ &lt;- ZIO.loopDiscard[Any</span><span class="s0">,</span><span class="s1">Throwable</span><span class="s0">,</span><span class="s1">Boolean](</span><span class="s0">true</span><span class="s1">)(function</span><span class="s0">, </span><span class="s1">identity)(handleMessage)</span>
  <span class="s1">} </span><span class="s0">yield true</span>
<span class="s1">}</span>
</pre>

I stored the actors in [transactional memory](https://zio.dev/reference/stm/) instead of a plain concurrent data structure.
I wanted to implement actor restarts using memory transactions so I started with them at the beginning.

<pre>
  <span class="s0">private def </span><span class="s1">registerActor[T](actor: Actor[T]</span><span class="s0">, </span><span class="s1">actors: TMap[String</span><span class="s0">, </span><span class="s1">Actor[Nothing]]): Task[MessageDestination[T]] = </span><span class="s0">for </span><span class="s1">{</span>
    <span class="s1">actorId &lt;- ZIO.succeed(java.util.UUID.randomUUID().toString)</span>
    <span class="s1">_ &lt;- STM.atomically {</span>
      <span class="s1">actors.put(actorId</span><span class="s0">, </span><span class="s1">actor.asInstanceOf[Actor[Nothing]])</span>
    <span class="s1">}</span>
  <span class="s1">} </span><span class="s0">yield </span><span class="s1">ActorMessageDestination[T](actorId</span><span class="s0">, this</span><span class="s1">)</span>
</pre>

So far so good, but the implementation is not very useful.
The nice thing about the actor references that are returned from actor creation is that they are typed and easy to work with.
It's harder to keep the type signatures if we look up the references using a route string.

# Finding actors to talk to

At this point I searched around for more information about actor implementations and found this nice quote in a [Clojure article about state and the actor model](https://clojure.org/about/state#actors).

> It doesn’t let you fully leverage the efficiencies of being in the same process. It is quite possible to efficiently directly share a large immutable data structure between threads, but the actor model forces intervening conversations and, potentially, copying. Reads and writes get serialized and block each other, etc.

Since I was already optimizing for the single-process case, I decided to try using shared memory to lookup actors.

The problem is that actors are created during the initialization phase, but then messages from the outside world have to lookup these actors again.

In the multi-process case it makes sense to send a message to lookup an actor but I wanted to try using shared memory in the single-process case.

A simple example is a card game server:
  - players create games
  - each game is managed by an actor
  - players send commands to game actors they created

In the single-process case we could create game actors directly and store them in a concurrent data structure.
Instead of this, I implemented a game supervisor to experiment with the bootstrapping/initialization experience for actors.

When the actor system is initialized, it's easy to lose track of the actor references that are created.
One option is to wrap the actor system in an object that has references to important actors but then it would be nice if the actors could also see this directory of important actors.

Instead of wrapping the actor system I added an initialization method that stores the initial actor set in a directory data structure.

It's easier to preserve type signatures when there's a fixed set of actors.  

<pre>
<span class="s0">case class </span><span class="s1">GameDirectory(blackjackSupervisor: Option[MessageDestination[BlackjackSupervisorMessage]]</span><span class="s0">,</span>
                         <span class="s1">pokerSupervisor: Option[MessageDestination[PokerSupervisorMessage]])</span>
</pre>

<pre>
<span class="s1">test(</span><span class="s2">&quot;Creates a fixed set of actors&quot;</span><span class="s1">) {</span>
  <span class="s0">val </span><span class="s1">initializeZIO = ActorSystem.initialize(GameDirectory(None</span><span class="s0">, </span><span class="s1">None)</span><span class="s0">, </span><span class="s1">List(</span>
    <span class="s0">new </span><span class="s1">ActorInitializer[GameDirectory] {</span>
      <span class="s0">override type </span><span class="s1">MessageType = BlackjackSupervisorMessage</span>

      <span class="s0">override def </span><span class="s1">actorTemplate: Task[ActorTemplate[BlackjackSupervisorMessage]] = {</span>
        <span class="s0">val </span><span class="s1">value = ActorTemplate.handler((message: MessageType) =&gt; ZIO.succeed(</span><span class="s0">true</span><span class="s1">))</span>
        <span class="s1">ZIO.succeed(value)</span>
      <span class="s1">}</span>

      <span class="s0">override def </span><span class="s1">injectActorReference(messageDestination: MessageDestination[BlackjackSupervisorMessage]</span><span class="s0">, </span><span class="s1">directory: GameDirectory): GameDirectory = {</span>
        <span class="s1">directory.copy(blackjackSupervisor = Some(messageDestination))</span>
      <span class="s1">}</span>
    <span class="s1">}</span>
  <span class="s1">))</span>
  <span class="s0">val </span><span class="s1">testZIO = </span><span class="s0">for </span><span class="s1">{</span>
    <span class="s1">actorSystem &lt;- initializeZIO</span>
    <span class="s1">resultPromise &lt;- Promise.make[Throwable</span><span class="s0">, </span><span class="s1">String]</span>
    <span class="s1">destination &lt;- ZIO.succeed(actorSystem.promiseMessageDestination(resultPromise))</span>
    <span class="s1">directory &lt;- actorSystem.directory</span>
    <span class="s1">_ &lt;- actorSystem.send(BlackjackSupervisorMessage(</span><span class="s2">&quot;Hello&quot;</span><span class="s0">, </span><span class="s1">replyTo = destination)</span><span class="s0">,</span>
      <span class="s1">directory.blackjackSupervisor.get)</span>
  <span class="s1">} </span><span class="s0">yield true</span>
  <span class="s1">assertZIO(testZIO)(Assertion.equalTo(</span><span class="s0">true</span><span class="s1">))</span>
<span class="s1">}</span>
</pre>

I wanted to have a way for the actor system to pass the directory down to actors but didn't end up implementing it.
The type signatures are tough to get right.

With this basic directory there's a way to get top level supervisor actors.
It doesn't help with child actors but there's more on that later.

# Supervising actors

Now that there is a game supervisor actor, we can send it a message to start a game actor.

<pre>
<span class="s0">private def </span><span class="s1">blackjackSupervisor(gameHandler: ActorTemplate[BlackjackGameMessage]) = {</span>
  <span class="s0">new </span><span class="s1">ActorInitializer[GameDirectory] {</span>
    <span class="s0">override type </span><span class="s1">MessageType = BlackjackSupervisorMessage</span>

    <span class="s0">override def </span><span class="s1">actorTemplate: Task[ActorTemplate[BlackjackSupervisorMessage]] = {</span>
      <span class="s0">val </span><span class="s1">value = ActorTemplate.handler((actorService: ActorService</span><span class="s0">,</span>
                                         <span class="s1">message: BlackjackSupervisorMessage) =&gt;</span>
        <span class="s1">message </span><span class="s0">match </span><span class="s1">{</span>
          <span class="s0">case </span><span class="s1">StartBlackJackGameMessage(replyTo) =&gt;</span>
            <span class="s0">for </span><span class="s1">{</span>
              <span class="s1">blackjackActor &lt;- actorService.startActor(gameHandler)</span>
              <span class="s1">_ &lt;- actorService.send(StartedBlackJackGameMessage(blackjackActor)</span><span class="s0">, </span><span class="s1">replyTo)</span>
            <span class="s1">} </span><span class="s0">yield true</span>
          <span class="s0">case </span><span class="s1">_ =&gt; ZIO.succeed(</span><span class="s0">true</span><span class="s1">)</span>
        <span class="s1">})</span>
      <span class="s1">ZIO.succeed(value)</span>
    <span class="s1">}</span>

    <span class="s0">override def </span><span class="s1">injectActorReference(messageDestination: MessageDestination[BlackjackSupervisorMessage]</span><span class="s0">,</span>
                                      <span class="s1">directory: GameDirectory): GameDirectory = {</span>
      <span class="s1">directory.copy(blackjackSupervisor = Some(messageDestination))</span>
    <span class="s1">}</span>
  <span class="s1">}</span>
<span class="s1">}</span>
</pre>

To implement this, the actor's message handler signature had to be changed.


<pre>
<span class="s0">private def </span><span class="s1">createActor[T](actorTemplate: ActorTemplate[T]</span><span class="s0">, </span><span class="s1">parentActor: Option[String]): Task[ActorState[T]] = {</span>
  <span class="s0">val </span><span class="s1">actorId = java.util.UUID.randomUUID().toString</span>
  <span class="s0">val </span><span class="s1">actorSystem = </span><span class="s0">this</span>
  <span class="s0">val </span><span class="s1">actorService = </span><span class="s0">new </span><span class="s1">ActorService {</span>
    <span class="s0">override def </span><span class="s1">startActor[M](template: ActorTemplate[M]): Task[MessageDestination[M]] = {</span>
      <span class="s0">for </span><span class="s1">{</span>
        <span class="s1">actor &lt;- createActor(template</span><span class="s0">, </span><span class="s1">Some(actorId))</span>
        <span class="s1">actorMessageDestination &lt;- registerActor(actor)</span>
      <span class="s1">} </span><span class="s0">yield </span><span class="s1">actorMessageDestination</span>
    <span class="s1">}</span>
  <span class="s1">}</span>
  <span class="s1">actorTemplate </span><span class="s0">match </span><span class="s1">{</span>
    <span class="s0">case </span><span class="s1">HandlerActorTemplate(handler) =&gt;</span>
      <span class="s0">for </span><span class="s1">{</span>
        <span class="s1">inbox &lt;- zio.Queue.bounded[T](</span><span class="s3">100</span><span class="s1">)</span>
        <span class="s1">actor &lt;- ZIO.succeed(</span><span class="s0">new </span><span class="s1">Actor(actorId</span><span class="s0">, </span><span class="s1">inbox))</span>
        <span class="s1">actorFibre &lt;- </span><span class="s0">if </span><span class="s1">(parentActor.isEmpty)</span>
          <span class="s1">ActorSystem.actorLoop(actorService</span><span class="s0">, </span><span class="s1">actor</span><span class="s0">, </span><span class="s1">handler).forkDaemon</span>
        <span class="s0">else</span>
          <span class="s1">ActorSystem.actorLoop(actorService</span><span class="s0">, </span><span class="s1">actor</span><span class="s0">, </span><span class="s1">handler).fork</span>
      <span class="s1">} </span><span class="s0">yield </span><span class="s1">ActorState(phase = ActorState.Running()</span><span class="s0">, </span><span class="s1">parent = parentActor</span><span class="s0">, </span><span class="s1">children = Set()</span><span class="s0">, </span><span class="s1">actor = actor</span><span class="s0">, </span><span class="s1">fiber = actorFibre)</span>
  <span class="s1">}</span>
<span class="s1">}</span>
</pre>

When the game supervisor actor creates a child game actor, this child relationship should be registered internally.
To hold on to child information and for some other bookkeeping I created an `ActorState` class.

Considering actor restarts, there's a window here where the actor is in the process of stopping or restarting but it's still processing a message.
I should handle the case where this processing creates a child actor but it's tough to think about so I skipped it.

<pre>
<span class="s0">private def </span><span class="s1">registerActor[T](actorState: ActorState[T]): Task[MessageDestination[T]] = </span><span class="s0">for </span><span class="s1">{</span>
  <span class="s1">actorId &lt;- ZIO.succeed(actorState.actor.actorId)</span>
  <span class="s1">_ &lt;- STM.atomically {</span>
    <span class="s0">for </span><span class="s1">{</span>
      <span class="s1">_ &lt;- </span><span class="s0">if </span><span class="s1">(actorState.parent.isDefined)</span>
        <span class="s0">for </span><span class="s1">{</span>
          <span class="s1">actorRefOption &lt;- actors.get(actorState.parent.get)</span>
          <span class="s1">_ &lt;- actorRefOption </span><span class="s0">match </span><span class="s1">{</span>
            <span class="s0">case </span><span class="s1">Some(actorRef) =&gt; actorRef.update(actorState =&gt; {</span>
              <span class="s2">// Parent could be stopped or restarting</span>
              <span class="s1">actorState.copy(children = actorState.children + actorId)</span>
            <span class="s1">})</span>
            <span class="s0">case </span><span class="s1">None =&gt; STM.succeed(())</span>
          <span class="s1">}</span>
        <span class="s1">} </span><span class="s0">yield </span><span class="s1">()</span>
      <span class="s0">else</span>
        <span class="s1">STM.succeed(())</span>
      <span class="s1">actorStateRef &lt;- TRef.make(actorState.asInstanceOf[ActorState[Nothing]])</span>
      <span class="s1">_ &lt;- actors.put(actorId</span><span class="s0">, </span><span class="s1">actorStateRef)</span>
    <span class="s1">} </span><span class="s0">yield </span><span class="s1">()</span>
  <span class="s1">}</span>
<span class="s1">} </span><span class="s0">yield new </span><span class="s1">ActorMessageDestination[T](actorId</span><span class="s0">, this</span><span class="s1">) {</span>
  <span class="s0">def </span><span class="s1">send(message: T): Task[Boolean] = </span><span class="s0">for </span><span class="s1">{</span>
    <span class="s1">actorOption &lt;- STM.atomically(actors.get(</span><span class="s0">this</span><span class="s1">.actorId))</span>
    <span class="s1">actorRef &lt;- ZIO.getOrFail(actorOption)</span>
    <span class="s1">actorState &lt;- STM.atomically(actorRef.get)</span>
    <span class="s1">_ &lt;- actorState.actor.asInstanceOf[Actor[T]].inbox.offer(message)</span>
  <span class="s1">} </span><span class="s0">yield true</span>
<span class="s1">}</span>
</pre>

I could have used [ZIO Concurrent Refs](https://zio.dev/reference/concurrency/ref) at this point but transactional memory will be used for restarts later. 

# Actors that remember things

The game supervisor can create game actors but these game actors don't have built in state.
We could use an external variable or `Ref` to hold this state but having some kind of immutable state is a natural use case.

I'm guessing that it's common to process messages that don't change the actor state so I made updating the actor state explicit.

<pre>
<span class="s0">private def </span><span class="s1">dealerGameActor = {</span>
  <span class="s1">ActorTemplate.stateful(()=&gt; BlackjackTable(player1Hand = List())</span><span class="s0">,</span>
    <span class="s1">(actorSystem</span><span class="s0">, </span><span class="s1">message: BlackjackGameMessage</span><span class="s0">, </span><span class="s1">table: BlackjackTable) =&gt; message </span><span class="s0">match </span><span class="s1">{</span>
    <span class="s0">case </span><span class="s1">ShowHand(replyTo) =&gt; </span><span class="s0">for </span><span class="s1">{</span>
      <span class="s1">_ &lt;- replyTo.send(Hand(table.player1Hand))</span>
    <span class="s1">} </span><span class="s0">yield </span><span class="s1">StatefulActor.Continue()</span>
    <span class="s0">case </span><span class="s1">Hit(replyTo) =&gt; </span><span class="s0">for </span><span class="s1">{</span>
      <span class="s1">newTable &lt;- ZIO.attempt {</span>
        <span class="s1">table.copy(player1Hand = Card(</span><span class="s2">&quot;Hearts&quot;</span><span class="s0">, </span><span class="s2">&quot;Queen&quot;</span><span class="s1">) +: table.player1Hand)</span>
      <span class="s1">}</span>
      <span class="s1">_ &lt;- replyTo.send(Hand(newTable.player1Hand))</span>
    <span class="s1">} </span><span class="s0">yield </span><span class="s1">StatefulActor.UpdateState(newTable)</span>
    <span class="s0">case </span><span class="s1">_ =&gt; ZIO.succeed(StatefulActor.Continue())</span>
  <span class="s1">})</span>
<span class="s1">}</span>
</pre>

<pre>
<span class="s1">test(</span><span class="s2">&quot;Can send messages to a stateful game actor created by the supervisor&quot;</span><span class="s1">) {</span>
  <span class="s0">val </span><span class="s1">initializeZIO = ActorSystem.initialize(GameDirectory(None</span><span class="s0">, </span><span class="s1">None)</span><span class="s0">, </span><span class="s1">List(</span>
    <span class="s1">blackjackSupervisor(dealerGameActor)</span><span class="s0">,</span>
  <span class="s1">))</span>
  <span class="s0">val </span><span class="s1">testZIO = </span><span class="s0">for </span><span class="s1">{</span>
    <span class="s1">actorSystem &lt;- initializeZIO</span>
    <span class="s1">directory &lt;- actorSystem.directory</span>
    <span class="s1">gameStarted &lt;- startBlackjackGame(directory).flatMap(_.await)</span>
    <span class="s1">gameActor = gameStarted.get</span>
    <span class="s1">gameReply1 &lt;- MessageDestination.promise[BlackjackGameMessage](destination =&gt; {</span>
      <span class="s1">gameActor.send(Hit(destination))</span>
    <span class="s1">}).flatMap(_.await)</span>
    <span class="s1">gameReply2 &lt;- MessageDestination.promise[BlackjackGameMessage](destination =&gt; {</span>
      <span class="s1">gameActor.send(Hit(destination))</span>
    <span class="s1">}).flatMap(_.await)</span>
    <span class="s1">gameReply3 &lt;- MessageDestination.promise[BlackjackGameMessage](destination =&gt; {</span>
      <span class="s1">gameActor.send(Hit(destination))</span>
    <span class="s1">}).flatMap(_.await)</span>
  <span class="s1">} </span><span class="s0">yield </span><span class="s1">List(gameReply1</span><span class="s0">,</span><span class="s1">gameReply2</span><span class="s0">, </span><span class="s1">gameReply3)</span>

  <span class="s0">val </span><span class="s1">expectedHand1 = Hand(</span>
    <span class="s1">hand = List(Card(</span>
      <span class="s1">suit = </span><span class="s2">&quot;Hearts&quot;</span><span class="s0">,</span>
      <span class="s1">rank = </span><span class="s2">&quot;Queen&quot;</span>
    <span class="s1">))</span>
  <span class="s1">)</span>
  <span class="s0">val </span><span class="s1">expectedHand2 = Hand(</span>
    <span class="s1">hand = List(Card(</span>
      <span class="s1">suit = </span><span class="s2">&quot;Hearts&quot;</span><span class="s0">,</span>
      <span class="s1">rank = </span><span class="s2">&quot;Queen&quot;</span>
    <span class="s1">)</span><span class="s0">, </span><span class="s1">Card(</span>
      <span class="s1">suit = </span><span class="s2">&quot;Hearts&quot;</span><span class="s0">,</span>
      <span class="s1">rank = </span><span class="s2">&quot;Queen&quot;</span>
    <span class="s1">))</span>
  <span class="s1">)</span>
  <span class="s0">val </span><span class="s1">expectedHand3 = Hand(</span>
    <span class="s1">hand = List(Card(</span>
      <span class="s1">suit = </span><span class="s2">&quot;Hearts&quot;</span><span class="s0">,</span>
      <span class="s1">rank = </span><span class="s2">&quot;Queen&quot;</span>
    <span class="s1">)</span><span class="s0">, </span><span class="s1">Card(</span>
      <span class="s1">suit = </span><span class="s2">&quot;Hearts&quot;</span><span class="s0">,</span>
      <span class="s1">rank = </span><span class="s2">&quot;Queen&quot;</span>
    <span class="s1">)</span><span class="s0">, </span><span class="s1">Card(</span>
      <span class="s1">suit = </span><span class="s2">&quot;Hearts&quot;</span><span class="s0">,</span>
      <span class="s1">rank = </span><span class="s2">&quot;Queen&quot;</span>
    <span class="s1">))</span>
  <span class="s1">)</span>
  <span class="s1">assertZIO(testZIO)(Assertion.equalTo(List(expectedHand1</span><span class="s0">,</span>
    <span class="s1">expectedHand2</span><span class="s0">,</span>
    <span class="s1">expectedHand3)))</span>
<span class="s1">}</span>
</pre>

Stateful actors were implemented by adding a new actor loop.
It was kind of nice to have separate implementations for the stateless and stateful case.
Doing it this way also meant that I didn't have to change my previous tests.
It probably makes sense to have only one implementation in general but this makes it easier to experiment with different types of actor creation APIs.

<pre>
  <span class="s0">private def </span><span class="s1">createActorState[T](actorCreator: ActorService</span><span class="s0">, </span><span class="s1">actorTemplate: ActorTemplate[T]</span><span class="s0">, </span><span class="s1">daemonFibre: Boolean): Task[Actor[T]] = actorTemplate </span><span class="s0">match </span><span class="s1">{</span>
    <span class="s0">case </span><span class="s1">HandlerActorTemplate(handler) =&gt;</span>
      <span class="s0">for </span><span class="s1">{</span>
        <span class="s1">inbox &lt;- zio.Queue.bounded[T](</span><span class="s2">100</span><span class="s1">)</span>
        <span class="s1">actor &lt;- ZIO.succeed(</span><span class="s0">new </span><span class="s1">Actor(inbox))</span>
        <span class="s1">_ &lt;- </span><span class="s0">if </span><span class="s1">(daemonFibre)</span>
          <span class="s1">actorLoop(actorCreator</span><span class="s0">, </span><span class="s1">actor</span><span class="s0">, </span><span class="s1">handler).forkDaemon</span>
        <span class="s0">else</span>
          <span class="s1">actorLoop(actorCreator</span><span class="s0">, </span><span class="s1">actor</span><span class="s0">, </span><span class="s1">handler).fork</span>
      <span class="s1">} </span><span class="s0">yield </span><span class="s1">actor</span>
    <span class="s0">case </span><span class="s1">template: StatefulActorTemplate[T] =&gt; </span><span class="s0">for </span><span class="s1">{</span>
      <span class="s1">inbox &lt;- zio.Queue.bounded[T](</span><span class="s2">100</span><span class="s1">)</span>
      <span class="s1">actor &lt;- ZIO.succeed(</span><span class="s0">new </span><span class="s1">Actor(inbox))</span>
      <span class="s1">initialState = template.initialStateSupplier.apply()</span>
      <span class="s1">handler  = template.handler</span>
      <span class="s1">_ &lt;- </span><span class="s0">if </span><span class="s1">(daemonFibre)</span>
        <span class="s1">statefulActorLoop(actorCreator</span><span class="s0">, </span><span class="s1">actor</span><span class="s0">, </span><span class="s1">initialState</span><span class="s0">, </span><span class="s1">handler).forkDaemon</span>
      <span class="s0">else</span>
        <span class="s1">statefulActorLoop(actorCreator</span><span class="s0">, </span><span class="s1">actor</span><span class="s0">, </span><span class="s1">initialState</span><span class="s0">, </span><span class="s1">handler).fork</span>
    <span class="s1">} </span><span class="s0">yield </span><span class="s1">actor</span>
  <span class="s1">}</span>

  <span class="s0">private def </span><span class="s1">actorLoop[T](actorCreator: ActorService</span><span class="s0">, </span><span class="s1">actor: Actor[T]</span><span class="s0">, </span><span class="s1">handler: (ActorService</span><span class="s0">, </span><span class="s1">T) =&gt; Task[Boolean]): Task[Boolean] = {</span>
    <span class="s0">val </span><span class="s1">handleMessage: Boolean =&gt; Task[Boolean] = (state: Boolean) =&gt; </span><span class="s0">for </span><span class="s1">{</span>
      <span class="s1">message &lt;- actor.inbox.take</span>
      <span class="s1">_ &lt;- handler(actorCreator</span><span class="s0">, </span><span class="s1">message)</span>
    <span class="s1">} </span><span class="s0">yield true</span>
    <span class="s0">for </span><span class="s1">{</span>
      <span class="s1">_ &lt;- ZIO.iterate[Any</span><span class="s0">, </span><span class="s1">Throwable</span><span class="s0">, </span><span class="s1">Boolean](</span><span class="s0">true</span><span class="s1">)(_ != </span><span class="s0">false</span><span class="s1">)(handleMessage)</span>
    <span class="s1">} </span><span class="s0">yield true</span>
  <span class="s1">}</span>

  <span class="s0">private def </span><span class="s1">statefulActorLoop[S</span><span class="s0">,</span><span class="s1">T](actorCreator: ActorService</span><span class="s0">, </span><span class="s1">actor: Actor[T]</span><span class="s0">, </span><span class="s1">state: S</span><span class="s0">, </span><span class="s1">handler: (ActorService</span><span class="s0">, </span><span class="s1">T</span><span class="s0">, </span><span class="s1">S) =&gt; Task[StatefulActor.Result[S]]): Task[Boolean] = {</span>
    <span class="s0">val </span><span class="s1">handleMessage = </span><span class="s0">for </span><span class="s1">{</span>
      <span class="s1">message &lt;- actor.inbox.take</span>
      <span class="s1">result &lt;- handler(actorCreator</span><span class="s0">, </span><span class="s1">message</span><span class="s0">, </span><span class="s1">state)</span>
    <span class="s1">} </span><span class="s0">yield </span><span class="s1">result</span>
    <span class="s1">handleMessage.flatMap {</span>
      <span class="s0">case </span><span class="s1">StatefulActor.Continue() =&gt; statefulActorLoop(actorCreator</span><span class="s0">, </span><span class="s1">actor</span><span class="s0">, </span><span class="s1">state</span><span class="s0">, </span><span class="s1">handler)</span>
      <span class="s0">case </span><span class="s1">StatefulActor.UpdateState(newState) =&gt; statefulActorLoop(actorCreator</span><span class="s0">, </span><span class="s1">actor</span><span class="s0">, </span><span class="s1">newState</span><span class="s0">, </span><span class="s1">handler)</span>
    <span class="s1">}</span>
  <span class="s1">}</span>
</pre>

# Waking up sleeping actors

Having in-memory actors is nice because they can hold on to some state without loading it from disk or a database.

The problem is that we want to restore these actors from storage to memory after a restart or if they are shut down due to inactivity.

In this test case, we have a 3-level supervision structure for a simple home automation/monitoring system:
- The (singleton) `houseSupervisor` actor supervises all `house` actors
- Many `house` actors supervise their own set of `temperatureSensor` actors
- `temperatureSensor` actors just receive sensor measurements with the current temperature and keep track of the max temperature.

There are few reasons why we might want to activate actors on demand and deactivate them when they haven't received messages for a while.
We might expect a lot of sensor owners to turn off their sensors for long periods of time, some owners might buy newer versions and stop using old ones.
Also, in a prototype stage, we might just restart the server and let the system reactivate sensors when it receives messages from them.

<pre>
<span class="s1">test(</span><span class="s2">&quot;Reactivates a persisted temperature sensor actor and sends it a message&quot;</span><span class="s1">) {</span>
  <span class="s0">val </span><span class="s1">houseDatabase: List[House] = List(House(</span><span class="s2">&quot;123GreenStreet&quot;</span><span class="s1">))</span>
  <span class="s0">val </span><span class="s1">temperatureSensorsDatabase: List[TemperatureSensor] = List(TemperatureSensor(</span><span class="s2">&quot;123GreenStreet&quot;</span><span class="s0">, </span><span class="s2">&quot;MainFloorSensor&quot;</span><span class="s1">))</span>

  <span class="s0">val </span><span class="s1">temperatureActorTemplate = ActorTemplate.stateful[TemperatureSensorCommand</span><span class="s0">, </span><span class="s1">Double](() =&gt; Double.MinValue</span><span class="s0">, </span><span class="s1">(actorService</span><span class="s0">, </span><span class="s1">message</span><span class="s0">, </span><span class="s1">maxTemp) =&gt; message </span><span class="s0">match </span><span class="s1">{</span>
    <span class="s0">case </span><span class="s1">RecordTemperature(celsius</span><span class="s0">, </span><span class="s1">maxTempReply) =&gt;</span>
      <span class="s0">for </span><span class="s1">{</span>
        <span class="s1">newMaxTemp &lt;- ZIO.succeed(Math.max(celsius</span><span class="s0">, </span><span class="s1">maxTemp))</span>
        <span class="s1">_ &lt;- maxTempReply.send(MaxTemperature(newMaxTemp))</span>
      <span class="s1">} </span><span class="s0">yield </span><span class="s1">StatefulActor.UpdateState(newMaxTemp)</span>
    <span class="s0">case </span><span class="s1">_ =&gt; ZIO.succeed(StatefulActor.Continue())</span>
  <span class="s1">})</span>

  <span class="s0">val </span><span class="s1">homeDirectoryInitializer = houseWithSensorsSupervisor(houseDatabase</span><span class="s0">,</span>
    <span class="s1">temperatureSensorsDatabase</span><span class="s0">,</span>
    <span class="s1">temperatureActorTemplate)</span>
  <span class="s0">for </span><span class="s1">{</span>
    <span class="s1">actorSystem &lt;- ActorSystem.initialize(HomeAutomationDirectory(None</span><span class="s0">, </span><span class="s1">None)</span><span class="s0">, </span><span class="s1">List(</span>
      <span class="s1">homeDirectoryInitializer</span>
    <span class="s1">))</span>
    <span class="s1">sensorId = </span><span class="s2">&quot;MainFloorSensor&quot;</span>
    <span class="s1">temperatureSensorOption &lt;- getOrActivateTemperatureSensor(sensorId</span><span class="s0">, </span><span class="s1">actorSystem</span><span class="s0">, </span><span class="s1">temperatureSensorsDatabase)</span>
    <span class="s1">temperatureSensor = temperatureSensorOption.get</span>
    <span class="s1">directory &lt;- actorSystem.directory</span> 
    <span class="s1">maxTemp1 &lt;- MessageDestination.promise[MaxTemperature](destination =&gt; {</span>
      <span class="s1">temperatureSensor.send(RecordTemperature(</span><span class="s3">21</span><span class="s0">, </span><span class="s1">destination))</span>
    <span class="s1">}).flatMap(_.await)</span>
    <span class="s1">maxTemp2 &lt;- MessageDestination.promise[MaxTemperature](destination =&gt; {</span>
      <span class="s1">temperatureSensor.send(RecordTemperature(</span><span class="s3">23</span><span class="s0">, </span><span class="s1">destination))</span>
    <span class="s1">}).flatMap(_.await)</span>
    <span class="s1">maxTemp3 &lt;- MessageDestination.promise[MaxTemperature](destination =&gt; {</span>
      <span class="s1">temperatureSensor.send(RecordTemperature(</span><span class="s3">20</span><span class="s0">, </span><span class="s1">destination))</span>
    <span class="s1">}).flatMap(_.await)</span>
  <span class="s1">} </span><span class="s0">yield </span><span class="s1">assertTrue(maxTemp1.celsius == </span><span class="s3">21 </span><span class="s1">&amp;&amp;</span>
    <span class="s1">maxTemp2.celsius == </span><span class="s3">23 </span><span class="s1">&amp;&amp;</span>
    <span class="s1">maxTemp3.celsius == </span><span class="s3">23</span><span class="s1">)</span>
<span class="s1">}</span>
</pre>

In the test case I'm trying to model a situation where the houses and temperature sensor configuration is stored in some sort of database.
In a real implementation we could probably manage this configuration with a non-actor web application or service.

The key here is how we know if the temperature sensor actor we're trying to send a message to is inactive.
As part of the experiment, I decided to use a transactional map to store this route to the sensor.
The nice thing about this is that if the sensor actor is active, then recording a temperature just requires one memory lookup and one message send.

If the sensor's associated house actor is not active, it also needs to activate that.

<pre>
  <span class="s0">private def </span><span class="s1">houseWithSensorsSupervisor(houseDatabase: List[House]</span><span class="s0">,</span>
                                         <span class="s1">temperatureSensorsDatabase: List[TemperatureSensor]</span><span class="s0">,</span>
                                         <span class="s1">temperatureSensorTemplate: ActorTemplate[TemperatureSensorCommand]) = {</span>
    <span class="s0">val </span><span class="s1">houseActorTemplate = (houseRoutes: HouseRoutes) =&gt; ActorTemplate.handler((actorService: ActorService</span><span class="s0">, </span><span class="s1">houseCommand: HouseCommand) =&gt; houseCommand </span><span class="s0">match </span><span class="s1">{</span>
      <span class="s0">case </span><span class="s1">GetHouseSummary(replyTo) =&gt; replyTo.send(HouseSummary())</span>
      <span class="s0">case </span><span class="s1">ActivateTemperatureSensor(temperatureSensorId</span><span class="s0">, </span><span class="s1">replyTo) =&gt; </span><span class="s0">for </span><span class="s1">{</span>
        <span class="s1">temperatureSensorOption &lt;- ZIO.succeed(temperatureSensorsDatabase.find(_.sensorId == temperatureSensorId))</span>
        <span class="s1">_ &lt;- </span><span class="s0">if </span><span class="s1">(temperatureSensorOption.isEmpty)</span>
          <span class="s1">replyTo.send(None)</span>
        <span class="s0">else</span>
          <span class="s0">for </span><span class="s1">{</span>
            <span class="s1">temperatureActor &lt;- actorService.startActor(temperatureSensorTemplate)</span>
            <span class="s1">_ &lt;- STM.atomically(houseRoutes.temperatureSensorRoutes.routes.put(temperatureSensorId</span><span class="s0">, </span><span class="s1">temperatureActor))</span>
            <span class="s1">_ &lt;- replyTo.send(Some(temperatureActor))</span>
          <span class="s1">} </span><span class="s0">yield </span><span class="s1">()</span>
      <span class="s1">} </span><span class="s0">yield true</span>
    <span class="s1">})</span>
    <span class="s0">new </span><span class="s1">ActorInitializer[HomeAutomationDirectory] {</span>
      <span class="s0">override type </span><span class="s1">MessageType = HouseSupervisorMessage</span>

      <span class="s0">override def </span><span class="s1">initialize: Task[(ActorTemplate[HouseSupervisorMessage]</span><span class="s0">, </span><span class="s1">(MessageDestination[HouseSupervisorMessage]</span><span class="s0">, </span><span class="s1">HomeAutomationDirectory) =&gt; Task[HomeAutomationDirectory])] = {</span>
        <span class="s0">for </span><span class="s1">{</span>
          <span class="s1">temperatureSensorMap &lt;- STM.atomically(TMap.empty[String</span><span class="s0">, </span><span class="s1">MessageDestination[TemperatureSensorCommand]])</span>
          <span class="s1">temperatureSensorRoutes = TemperatureSensorRoutes(temperatureSensorMap)</span>
          <span class="s1">routes &lt;- STM.atomically(TMap.empty[String</span><span class="s0">, </span><span class="s1">MessageDestination[HouseCommand]])</span>
          <span class="s1">houseRouteObject = HouseRoutes(routes</span><span class="s0">, </span><span class="s1">temperatureSensorRoutes)</span>
          <span class="s1">value = ActorTemplate.stateful(() =&gt; houseRouteObject</span><span class="s0">,</span>
            <span class="s1">(actorService: ActorService</span><span class="s0">, </span><span class="s1">message: HouseSupervisorMessage</span><span class="s0">, </span><span class="s1">houseRoutes: HouseRoutes) =&gt; {</span>
              <span class="s1">message </span><span class="s0">match </span><span class="s1">{</span>
                <span class="s0">case </span><span class="s1">ActivateHouse(houseId</span><span class="s0">, </span><span class="s1">replyTo) =&gt; </span><span class="s0">for </span><span class="s1">{</span>
                  <span class="s1">lookupHouse &lt;- ZIO.succeed(houseDatabase.find(_.houseId == houseId))</span>
                  <span class="s1">_ &lt;- lookupHouse </span><span class="s0">match </span><span class="s1">{</span>
                    <span class="s0">case </span><span class="s1">Some(house) =&gt;</span>
                      <span class="s0">for </span><span class="s1">{</span>
                        <span class="s1">houseActor &lt;- actorService.startActor(houseActorTemplate(houseRoutes))</span>
                        <span class="s1">_ &lt;- STM.atomically(routes.put(houseId</span><span class="s0">, </span><span class="s1">houseActor))</span>
                        <span class="s1">_ &lt;- replyTo.send(Some(houseActor))</span>
                      <span class="s1">} </span><span class="s0">yield true</span>
                    <span class="s0">case </span><span class="s1">None =&gt; replyTo.send(None)</span>
                  <span class="s1">}</span>

                <span class="s1">} </span><span class="s0">yield </span><span class="s1">StatefulActor.Continue()</span>
              <span class="s1">}</span>
            <span class="s1">}</span>
          <span class="s1">)</span>
        <span class="s1">} </span><span class="s0">yield </span><span class="s1">(value</span><span class="s0">, </span><span class="s1">(houseSupervisor</span><span class="s0">, </span><span class="s1">directory) =&gt; ZIO.succeed(directory.copy(</span>
          <span class="s1">homeSupervisor = Some(houseSupervisor)</span><span class="s0">,</span>
          <span class="s1">houseRoutes = Some(houseRouteObject)</span>
        <span class="s1">)))</span>
      <span class="s1">}</span>
    <span class="s1">}</span>
  <span class="s1">}</span>
</pre>

From the incoming message side, every time the message comes in we need to check if the sensor actor is active.
If the sensor actor isn't active, the house actor may also need to be activated.
It's nice to be in a single process here because finding out if an actor is active is faster.

<pre>
<span class="s0">private def </span><span class="s1">getOrActivateTemperatureSensor(temperatureSensorId: String</span><span class="s0">,</span>
                                           <span class="s1">actorSystem: ActorSystem[HomeAutomationDirectory]</span><span class="s0">,</span>
                                           <span class="s1">temperatureSensorDatabase: List[TemperatureSensor]): Task[Option[MessageDestination[TemperatureSensorCommand]]] = {</span>
  <span class="s0">for </span><span class="s1">{</span>
    <span class="s1">directory &lt;- actorSystem.directory</span>
    <span class="s1">maybeTemperatureSensorRoutes = directory.houseRoutes</span>
      <span class="s1">.map(_.temperatureSensorRoutes)</span>
    <span class="s1">temperatureActorOption &lt;- maybeTemperatureSensorRoutes.get</span>
      <span class="s1">.temperatureActorOption(temperatureSensorId)</span>
    <span class="s1">temperatureSensorOption &lt;- temperatureActorOption </span><span class="s0">match </span><span class="s1">{</span>
      <span class="s0">case </span><span class="s1">Some(temperatureSensor) =&gt; ZIO.succeed(Some(temperatureSensor))</span>
      <span class="s0">case </span><span class="s1">None =&gt; </span><span class="s0">for </span><span class="s1">{</span>
        <span class="s1">temperatureSensor &lt;- ZIO.succeed(temperatureSensorDatabase.find(_.sensorId == temperatureSensorId))</span>
        <span class="s1">sensorOption &lt;- temperatureSensor </span><span class="s0">match </span><span class="s1">{</span>
          <span class="s0">case </span><span class="s1">Some(sensor) =&gt; </span><span class="s0">for </span><span class="s1">{</span>
            <span class="s1">houseActorOption &lt;- getOrActivateHouse(sensor.houseId</span><span class="s0">, </span><span class="s1">actorSystem)</span>
            <span class="s1">sensor &lt;- MessageDestination.promise[Option[MessageDestination[TemperatureSensorCommand]]](destination =&gt;</span>
              <span class="s1">houseActorOption.get.send(ActivateTemperatureSensor(temperatureSensorId</span><span class="s0">, </span><span class="s1">destination))</span>
            <span class="s1">).flatMap(_.await)</span>

          <span class="s1">} </span><span class="s0">yield </span><span class="s1">sensor</span>
          <span class="s0">case </span><span class="s1">None =&gt; ZIO.succeed(None)</span>
        <span class="s1">}</span>
      <span class="s1">} </span><span class="s0">yield </span><span class="s1">sensorOption</span>
    <span class="s1">}</span>
  <span class="s1">} </span><span class="s0">yield </span><span class="s1">temperatureSensorOption</span>
<span class="s1">}</span>

<span class="s0">private def </span><span class="s1">getOrActivateHouse(houseId: String</span><span class="s0">, </span><span class="s1">actorSystem: ActorSystem[HomeAutomationDirectory]): Task[Option[MessageDestination[HouseCommand]]] = {</span>
  <span class="s0">for </span><span class="s1">{</span>
    <span class="s1">directory &lt;- actorSystem.directory</span>
    <span class="s1">houseActorOption &lt;- directory.houseRoutes.get.houseActorOption(houseId)</span>
    <span class="s1">houseActorActivateOption &lt;- houseActorOption </span><span class="s0">match </span><span class="s1">{</span>
      <span class="s0">case </span><span class="s1">Some(houseActor) =&gt; ZIO.succeed(Some(houseActor))</span>
      <span class="s0">case </span><span class="s1">None =&gt; MessageDestination.promise[Option[MessageDestination[HouseCommand]]](destination =&gt;</span>
        <span class="s1">directory.homeSupervisor.get.send(ActivateHouse(houseId</span><span class="s0">, </span><span class="s1">destination))</span>
      <span class="s1">).flatMap(_.await)</span>
    <span class="s1">}</span>
  <span class="s1">} </span><span class="s0">yield </span><span class="s1">houseActorActivateOption</span>
<span class="s1">}</span>
</pre>

Using a shared data structure to store these routes doesn't fit the actor model, but this was an interesting experiment to try out.

## Waking up is hard to do

With a deep supervision hierarchy, waking up a descendant actor that is down a few levels requires waking up ancestor actors up the chain.
Having a flatter hierarchy means less levels to traverse.

Another question is if an ancestor actor wakes up, should it wake up child actors eagerly ahead of time or lazily as messages come in.
If the ancestor has a large number of descendants then a mix of eager activation and lazy activation will probably be the best.

## Talking through the supervisor

The safest way to communicate with child actors is to route messages through the supervisor.
I'm not sure what the performance/latency overhead of this ends up looking like but a lot of use cases don't need that level of safety.

At a minimum it seems like the top-level actor's inbox could fill up quickly if it's forwarding all the messages for all its descendant actors.
Any blocking action in a supervising actor would hold up messages for descendant actors.

Users playing an interactive game could probably tolerate some message loss/latency while a game actor initializes in exchange for better latency while playing the game.

A home automation system could probably miss some sensor readings while initializing its actors.
Consumer device users are likely to put away a device for long periods of time and start them up again randomly.

### Maintaining supervised actor collections in the supervisor

Supervised actors are tracked by the actor system, but it looks like it's common practice to manually keep track of supervised actors in the actor state at the same time.

One issue is if an exception is thrown in the message handler after the supervised actor is created then this actor might not be tracked in the supervisor's state.
This might not cause any logic bugs but these untracked actors could leak resources.

I thought of experimenting with different ways of maintaining the collection of child actors but my examples were too small to get any insight from doing this.


# Restarting actors with software transactional memory

After I had basic actor hierarchies implemented and worked through activating actors from a persistent store, the last thing to do was handle actor restarts.

I implemented the basic version of supervised actor restarts where all descendant actors are stopped.
Since the child actor [ZIO fibers](https://zio.dev/reference/fiber/) are child fibers of the parent actor ZIO fibers

<pre>
  <span class="s0">def </span><span class="s1">restartActor[T](messageDestination: MessageDestination[T]): Task[Option[ActorState[T]]] = messageDestination </span><span class="s0">match </span><span class="s1">{</span>
    <span class="s0">case </span><span class="s1">actorDestination: ActorMessageDestination[T] =&gt; </span><span class="s0">for </span><span class="s1">{</span>
      <span class="s1">_ &lt;- suspendActorById(actorDestination.actorId</span><span class="s0">, </span><span class="s1">ActorState.Suspended())</span>
      <span class="s1">suspendedActorState &lt;- STM.atomically {</span>
        <span class="s0">for </span><span class="s1">{</span>
          <span class="s1">actorStateOptionRef &lt;- actors.get(actorDestination.actorId)</span>
          <span class="s1">actorStateRef = actorStateOptionRef.get</span>
          <span class="s1">actorState &lt;- actorStateRef.get</span>
        <span class="s1">} </span><span class="s0">yield </span><span class="s1">actorState</span>
      <span class="s1">}</span>
      <span class="s4">// The restarted actor fiber is a child of the fiber that calls restart, this may not be the parent</span>
      <span class="s1">newActorState &lt;- createActor(suspendedActorState.actorTemplate</span><span class="s0">, </span><span class="s1">suspendedActorState.parent</span><span class="s0">, </span><span class="s1">suspendedActorState.actor.actorId)</span>
      <span class="s1">_ &lt;- STM.atomically {</span>
        <span class="s0">for </span><span class="s1">{</span>
          <span class="s1">actorStateOptionRef &lt;- actors.get(actorDestination.actorId)</span>
          <span class="s1">actorStateRef = actorStateOptionRef.get</span>
          <span class="s1">_ &lt;- actorStateRef.update(actorState =&gt; {</span>
            <span class="s0">if </span><span class="s1">(actorState.phase == ActorState.Suspended())</span>
              <span class="s1">newActorState</span>
            <span class="s0">else</span>
              <span class="s1">actorState</span>

          <span class="s1">})</span>
        <span class="s1">} </span><span class="s0">yield </span><span class="s1">()</span>
      <span class="s1">}</span>
    <span class="s1">} </span><span class="s0">yield </span><span class="s1">Some(newActorState.asInstanceOf[ActorState[T]])</span>
    <span class="s0">case </span><span class="s1">_ =&gt; ZIO.succeed(None)</span>
  <span class="s1">}</span>
</pre>

Here I use `ActorTemplate` to create a new ZIO fiber for the restarted actor.
It creates a new ZIO queue for the new inbox, but it could easily reuse the old one.

`ActorTemplate` isn't a great name for what this class does because it has all the initial state of the actor.
So if there was some kind of entity id associated with this actor, the `ActorTemplate` will always make an actor with the same entity id.

Transactional memory feels like a good way to get a lot of the corner cases here right earlier.

I used an initial transaction to mark the actor as suspended before doing the real restart work to prevent concurrent restarts.
I'm not sure how that would work out in production but it was nice that it was easy to do.

The code to suspend the actor is also used for a full actor stop, all child actors are stopped which should recursively stop any descendants.

<pre>
  <span class="s0">private def </span><span class="s1">suspendActorById(actorId: String</span><span class="s0">, </span><span class="s1">nextPhase: ActorState.Phase): Task[Option[ActorState[Nothing]]] =</span>
    <span class="s0">for </span><span class="s1">{</span>
      <span class="s1">actorState &lt;- STM.atomically {</span>
        <span class="s0">for </span><span class="s1">{</span>
          <span class="s1">actorStateOptional &lt;- actors.get(actorId)</span>
          <span class="s1">updatedState &lt;- actorStateOptional </span><span class="s0">match </span><span class="s1">{</span>
            <span class="s0">case </span><span class="s1">Some(actorStateRef) =&gt; </span><span class="s0">for </span><span class="s1">{</span>
              <span class="s1">actorState &lt;- actorStateRef.get</span>
              <span class="s1">newActorState = actorState.copy(phase = nextPhase)</span>
              <span class="s1">_ &lt;- actorStateRef.set(newActorState)</span>
            <span class="s1">} </span><span class="s0">yield </span><span class="s1">Some(newActorState)</span>
            <span class="s0">case </span><span class="s1">None =&gt; STM.succeed(None)</span>
          <span class="s1">}</span>
        <span class="s1">} </span><span class="s0">yield </span><span class="s1">updatedState</span>
      <span class="s1">}</span>
      <span class="s1">_ &lt;- actorState </span><span class="s0">match </span><span class="s1">{</span>
        <span class="s0">case </span><span class="s1">Some(actorState) =&gt; (</span><span class="s0">for </span><span class="s1">{</span>
          <span class="s1">_ &lt;- actorState.fiber.interrupt</span>
          <span class="s1">_ &lt;- actorState.actor.inbox.shutdown</span>
        <span class="s1">} </span><span class="s0">yield </span><span class="s1">())</span>
        <span class="s0">case </span><span class="s1">None =&gt; ZIO.succeed(())</span>
      <span class="s1">}</span>
      <span class="s1">_ &lt;- ZIO.foreachDiscard(actorState.toList.flatMap(_.children))(childActorId =&gt; </span><span class="s0">for </span><span class="s1">{</span>
        <span class="s1">_ &lt;- suspendActorById(childActorId</span><span class="s0">, </span><span class="s1">ActorState.Stopped())</span>
        <span class="s1">_ &lt;- STM.atomically {</span>
          <span class="s1">actors.delete(childActorId)</span>
        <span class="s1">}</span>
      <span class="s1">} </span><span class="s0">yield </span><span class="s1">())</span>
    <span class="s1">} </span><span class="s0">yield </span><span class="s1">actorState</span>
</pre>

# Top-level actors

It's standard advice but this implementation showed me the value of having a limited set of solid top-level supervisor actors.
It makes things a lot easier when you can assume that there's a set of actors that will always be there, always running.

I think that top-level actors should never restart or stop, and if they have to it's probably better to restart the whole actor process instead of trying to handle this case everywhere.

# Are actor restarts useful?  

Going through the exercise of implementing restarts made me think about the benefits of using this fault tolerance mechanism.
Actors can easily have large number of supervised descendant actors.
Even though ZIO fibers are lightweight, recreating 1000 or 10,000 descendant actors should probably be a rare event.

It seems like the main faults that restarts would help with are:
- The message handler is stuck on a blocking operation
- The message handler is waiting for a message that will never come (message deadlock)

These situations could probably be solved with restarts that don't stop child actors.
Restarts that stop child actors are probably best when they're limited to actors that don't have a lot of descendants.
In that case it could save a lot of time chasing down rare and heisenbug faults.

### Invalidating actor references

If restarting an actor stops all its descendant actors then any references to those descendants become invalid.
Going back to the earlier section, this is ok if all messages are forwarded from supervisor actors to descendant actors.
Unfortunately it kind of limits opportunities for caching actor references.

# Missing stuff

I implemented most of the things that I wanted to try out but few things I missed come to mind: 

- Dead letter mailboxes
- Actor inbox sizing and overflow behavior
- Sending exception errors to the supervisor
- Actors that can prevent themselves from stopping or restarting

# Future ideas for actor implementations

Implementing actor messaging, actor supervision and actor restarts worked through most of the ideas I wanted to try out. 
I tried out shared memory for managing actor routing and it looked promising.

Still, at the end of the experiment I got a few more ideas that I don't think I'll get to try.

## Communicating between different servers

Even when using a single process actor library, with enough scale, multiple servers will be required at some point.

For some applications it might be useful to make messaging between actor systems on different processes explicit.

For example if actor processes could only talk to each other through grpc then this communication could be consolidated in adapter/bridge actors that just handle grpc calls.
An advantage of doing it this way is that only the adapter/bridge has to communicate with serializable messages, the rest of the system can use values like lambdas in their messages.

This would also open up some options for plugging in different sharding and clustering methods for different use cases.

For some cases you want to be ensure that only one actor associated with an entity is running at one time, a game server fits this use case.
In other cases it could be ok to have multiple actors associated with an entity running for short periods of time as long as it eventually settles down to one actor.
Some simple sensor applications might be ok with multiple actors running for one entity for short interval.


## Breaking the actor model

Based on what I've read, most applications that use the actor model have to break out of it in some way.
Erlang applications are the exception but on the JVM it seems like using a non-actor library for API calls or database access is bound to happen at some point.

Given that two other cases came to mind where it might be worth breaking out of the actor model. 

### Stateless message processors

Code that wakes up actors, and routes messages to actors doesn't benefit from single-threaded state.
It seems reasonable to have message processors that are not single-threaded (single-fibered?).
These processors could be stateless in the REST service sense.

If we have to wake up a sensor actor, and this requires a database call, we should be able to do a blocking database lookup in the wakeup case.
A multi-fibered router/wakeup handler could do the blocking call while handling the active sensor case in separate fibers.

Akka recommends using [multiple actors](https://doc.akka.io/docs/akka/2.5/typed/routers.html) to handle this case, but it seems like a common use case where we could break out of the actor model and have things that process messages concurrently.

### Actors and blackboards

The actor model is at its best with messages that change state.
Within a message handler you're free to do any state updates.
For read heavy data, it seems like a version of the
[blackboard model](https://en.wikipedia.org/wiki/Blackboard_(design_pattern)) would be useful to reduce the number of request-reply messages.

Even in the multi-process case, if there's enough tolerance for latency, large blackboards could be synchronized between servers using [CRDTs](https://en.wikipedia.org/wiki/Conflict-free_replicated_data_type).

## Hiding supervisor hierarchies

I have a feeling that supervisor hierarchies should be hidden.
If the temperature sensor hierarchy is exposed as a route `/houseSupervisor/house123/temperature` then we might have to change a lot of code if we add another level of supervision `/houseSupervisor/stateHouseSupervisorCA/house123/temperature`.

On the other hand some routes like `/house123/sensor456` might be useful for sensors that can move between houses, but this should also be decoupled from the supervision hierarchy.

# Leftover ideas

- Passing the directory to actors, including updates that happen while the system is running
- Giving actors initialization/termination software transactions to register/deregister related routes
- Basic performance testing with different supervision hierarchies/routing strategies
- Actors need to be able to eagerly start other actors on restart
- Backpressure when actors are activated from persistence or lazy activated
- Actor timeouts for blocking operations
- Scheduling "tick" messages for polling or time based state changes

# Conclusions

TLDR, going through a toy implementation of actors was a useful exercise that left me with the following thoughts:

  - Single-process actor libraries offer interesting possibilities
  - If restarting an actor stops supervised/child actors, then child actor references are not stable
  - Consider shared memory or multiple copies of actor directories
    - Calling the phone operator vs mailing out phone books
    - CRDTs might be useful to maintain directory copies
  - It might be useful to separate actor routing concerns from the actor system 
  - It might be useful to break out of the actor model and allow concurrent message processing for special cases like routing
  - Actors might be able to share read-heavy data using a blackboard model
  - ZIO is nice to work with
  - Software Transactional Memory is useful for control-plane functionality
    - The railway switching station vs the rails and trains  
  - Working with Scala 3 was better than my previous experiences with earlier versions
    - Better compiler errors
    - More type system options
  - I revised this post a couple times but the ideas I was left with were not as organized as the ideas that I started with.
    - Thanks for reading!

