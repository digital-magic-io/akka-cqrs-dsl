# Akka-CQRS-DSL
[![Build Status](https://travis-ci.org/digital-magic-io/Akka-CQRS-DSL.svg?branch=master)](https://travis-ci.org/digital-magic-io/Akka-CQRS-DSL)
A convenient DSL for Command/Query Actor communication.

# Usage
Simply add the appropriate `Command` or `Query` trait to your messages, and you're good to go:
```
import io.digitalmagic.akka.dsl.API
import io.digitalmagic.akka.dsl.DSL._

case object GetValue extends API.Query[Int]
case class SetValue(newValue: Int) extends API.Command[Int]

val actor = system.actorOf(ExampleActor.props())

val num = 21
val future = for {
  tmp <- testService command SetValue(num)
  _   <- testService command SetValue(num + tmp)
  res <- testService query GetValue
} yield res

Await.result(future, 1 second) shouldBe 42
```