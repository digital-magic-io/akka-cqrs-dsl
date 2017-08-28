# akka-cqrs-dsl
[![Build Status](https://travis-ci.org/digital-magic-io/akka-cqrs-dsl.svg?branch=master)](https://travis-ci.org/digital-magic-io/akka-cqrs-dsl)
A convenient DSL for Command/Query Actor communication.

# Usage
Simply add the appropriate `Command` or `Query` trait to your messages, and you're good to go:
```
import io.digitalmagic.akka.dsl.API._

case object GetValue extends Query[Int]
case class SetValue(newValue: Int) extends Command[Int]

val actor = system.actorOf(ExampleActor.props())

val num = 21
val future = for {
  tmp <- actor command SetValue(num)
  _   <- actor command SetValue(num + tmp)
  res <- actor query GetValue
} yield res

Await.result(future, 1 second) shouldBe 42
```
