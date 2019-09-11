# akka-cqrs-dsl
[![Build Status](https://travis-ci.org/digital-magic-io/akka-cqrs-dsl.svg?branch=master)](https://travis-ci.org/digital-magic-io/akka-cqrs-dsl)
[![Download](https://api.bintray.com/packages/digital-magic/digital-magic/akka-cqrs-dsl/images/download.svg) ](https://bintray.com/digital-magic/digital-magic/akka-cqrs-dsl/_latestVersion)
[![License](http://img.shields.io/:license-Apache%202-red.svg)](http://www.apache.org/licenses/LICENSE-2.0.txt)

A convenient DSL for Command/Query Actor communication.

# Simple usage of command/query capability
Simply add the appropriate `Command` or `Query` trait to your messages, and you're good to go:
```scala
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

# Using interpreter for command/query handling

First define an trait with programs that extends ```EventSourcedPrograms``` trait:

```scala
trait Actor1Programs extends EventSourcedPrograms {

  import Actor1._

  // type of environment that will be passed into programs
  override type Environment = Unit

  // type of events emitted by programs
  override type EventType = Actor1Event
  override lazy val eventTypeTag: ClassTag[Actor1Event] = implicitly

  // type of state that programs have and it's processor
  override type State = Actor1State
  override lazy val stateTag: ClassTag[State] = implicitly
  override lazy val persistentState: PersistentStateProcessor[State] = new PersistentStateProcessor[State] {
    override def empty: State = Actor1State(0)
    override def process(state: State, event: EventType): State = event match {
      case ValueSet(value) => state.copy(value = value)
    }
  }

  // type of entity id, in case of non-sharded actor Unit can be used 
  override type EntityIdType = Unit

  // queries that programs can issue to other components
  override type QueryAlgebra[A] = CopK[TNilK, A]
  override val algebraIsQuery: IsQuery[QueryAlgebra] = implicitly

  // type of index programs maintain
  override type Index = EmptyIndexList
  override val clientRuntime: ClientRuntime[Index#List, Index] = implicitly

  // implement needed programs using effects available to them
  def getValue: Program[Int] = gets(_.value) // access the state
  def setValue(value: Int): Program[Unit] = for {
    // emit some logging
    _ <- log(_.info("setting value"))
    // emit event and automatically modify the state
    _ <- emit(ValueSet(value))
  } yield ()

  // extract environment from the request
  override def getEnvironment(r: Request[_]): Unit = ()

  // accept a state snapshot and perform migration if needed 
  override def processSnapshot(s: Any): Option[State] = s match {
    case x: State => Some(x)
    case _ => None
  }

  // return a program that will handle the passed request
  override def getProgram: Request ~> MaybeProgram = Lambda[Request ~> MaybeProgram] {
    case GetValue => Some(getValue)
    case SetValue(value) => Some(setValue(value))
    case _ => None
  }
}
```

Next implement the actor that will interpret those programs, it needs to extend ```EventSourcedActorWithInterpreter``` trait:

```scala
class Actor1 extends Actor1Programs with EventSourcedActorWithInterpreter {
  // extract this actor entity id
  override def entityId: Unit = ()
  // extract persistenceId for this actor
  override def persistenceId: String = s"${context.system.name}.Actor1"

  // some boiler plate for querying other components and using indexes
  override def interpreter: QueryAlgebra ~> LazyFuture = CopK.NaturalTransformation.summon[QueryAlgebra, LazyFuture]
  override def indexInterpreter: Index#Algebra ~> IndexFuture = CopK.NaturalTransformation.summon[Index#Algebra, IndexFuture]
  override def clientApiInterpreter: Index#ClientAlgebra ~> Const[Unit, *] = CopK.NaturalTransformation.summon[Index#ClientAlgebra, Const[Unit, *]]
  override def clientEventInterpreter: ClientEventInterpreter = implicitly
}
```

# Querying other components in programs

First, describe query algebra of API provider:

```scala
object Actor1 {
  // base trait for all queries
  sealed trait Query[A] extends API.Query[A]
  // concrete query
  case object GetValue extends Query[Int]

  // API for using in clients
  trait Api[Alg[A] <: CopK[_, A], Program[_]] {
    // this helper is needed to inject Queries into the program description
    this: ApiHelper[Query, Alg, Program] =>
    // provide a method for each concrete query
    def getValue: Program[Int] = GetValue
  }

  // interpreter of query algebra for example in terms of an actor
  def interpreter(actorSelection: ActorSelection): Query ~> LazyFuture = Lambda[Query ~> LazyFuture] {
    case q: GetValue.type => actorSelection query q
  }
}
```

In client programs:

```scala
trait AdderPrograms extends EventSourcedPrograms {

  // Define a co-product of query algebras needed
  type QueryAlgebra[A] = CopK[Actor1.Query ::: Actor2.Query ::: TNilK, A]
  // this line constructs a proof that only queries are used
  override val algebraIsQuery: IsQuery[QueryAlgebra] = implicitly

  // Instantiate needed APIs in the context of your program type
  val a1 = new ApiHelper[Actor1.Query, QueryAlgebra, Program] with Actor1.Api[QueryAlgebra, Program]
  val a2 = new ApiHelper[Actor2.Query, QueryAlgebra, Program] with Actor2.Api[QueryAlgebra, Program]

  // finally, you can use the APIs
  def queryAndAdd: Program[Int] = for {
    v1 <- a1.getValue
    v2 <- a2.getValue
    _  <- emit(MyEvent)
  } yield v1 + v2
```

When implementing an actor to interpret your programs you need:

```scala
// to accept interpreters for concrete query algebras
class Adder()(implicit val api1: Actor1.Query ~> LazyFuture, val api2: Actor2.Query ~> LazyFuture) extends AdderPrograms with EventSourcedActorWithInterpreter {
  // and to construct interpreter for co-product of those algebras 
  override def interpreter: QueryAlgebra ~> LazyFuture = CopK.NaturalTransformation.summon[QueryAlgebra, LazyFuture]
}
```

When instantiating the actor you need to instantiate API interpreters first:
```scala
implicit val actor1Api = Actor1.interpreter(system.actorSelection("/user/actor1"))
implicit val actor2Api = Actor2.interpreter(system.actorSelection("/user/actor2"))

val adder = system.actorOf(Adder.props, "adder")
``` 

# Using indexes in programs

First, describe indexes you want to use:

```scala
// the must be singleton objects in order for serialization to work correctly
object IndexExample {
  @SerialVersionUID(1)
  // specify entity id type and key type, in case of custom types it is necessary to implement StringRepresentable type class for them  
  implicit case object index1Api extends UniqueIndexApi.Base[String, String]
  @SerialVersionUID(1)
  implicit case object index2Api extends UniqueIndexApi.Base[String, String]
}
```

In programs you need to instantiate index APIs in the context of concrete program type:

```scala
trait IndexExample extends EventSourcedPrograms {

  // note that programs' entity id type and indexes' entity id type must match
  override type EntityIdType = String

  // construct list of all needed indexes
  type Index = EmptyIndexList# + [index1Api.type]# + [index2Api.type]
  override val clientRuntime: ClientRuntime[Index#List, Index] = implicitly

 // instantiate indexes APIs in program type context
  val a1 = new ApiHelper[index1Api.IndexApiType, Index#Algebra, Program] with index1Api.UpdateApi[Index#Algebra, Program]
  val a2 = new ApiHelper[index2Api.IndexApiType, Index#Algebra, Program] with index2Api.UpdateApi[Index#Algebra, Program]

 // now you can use those APIs in your programs
  def acquire: Program[Unit] = for {
    _ <- a1.acquire("abc")
    _ <- a2.acquire("abc")
    _ <- a1.acquire("def")
    _ <- a2.acquire("def")
    _ <- a1.acquire("ghi")
    _ <- a2.acquire("ghi")
  } yield ()

  val release: Program[Unit] = for {
    _ <- a1.release("abc")
    _ <- a2.release("abc")
    _ <- a1.release("def")
    _ <- a2.release("def")
    _ <- a1.release("ghi")
    _ <- a2.release("ghi")
  } yield ()
}
```

When implementing an actor to interpret your programs you need:

```scala
// accept interpreters for concrete indexes, it abstract over the communication channel with index interpretation
case class IndexExampleActor(entityId: String)(implicit I1: UniqueIndexInterface[IndexExample.index1Api.type], I2: UniqueIndexInterface[IndexExample.index2Api.type]) extends IndexExample with EventSourcedActorWithInterpreter {
  import IndexExample._

  // construct interpreters of different parts of client side APIs
  override def indexInterpreter: Index#Algebra ~> IndexFuture = CopK.NaturalTransformation.summon[Index#Algebra, IndexFuture]
  override def clientApiInterpreter: Index#ClientAlgebra ~> Const[Unit, *] = CopK.NaturalTransformation.summon[Index#ClientAlgebra, Const[Unit, *]]
  override def clientEventInterpreter: ClientEventInterpreter = implicitly
}
```

When instantiating the actor you need to instantiate index API interpreters first (in this case using actor-to-actor communication channel):
```scala
import IndexExample._
implicit val index1 = new ActorBasedUniqueIndex[index1Api.type](system.actorSelection("system/sharding/example"), system.actorSelection("system/sharding/index1"))
implicit val index2 = new ActorBasedUniqueIndex[index2Api.type](system.actorSelection("system/sharding/example"), system.actorSelection("system/sharding/index2"))
```

And then start needed index actors (note that those actors are sharded):
```scala
UniqueIndexActorDef(index1Api, "index1").start(clusterSharding, clusterShardingSettings)
UniqueIndexActorDef(index2Api, "index2").start(clusterSharding, clusterShardingSettings)
``` 

In order to query index you can use ```UniqueIndexApi.QueryApi``` as any other query API.