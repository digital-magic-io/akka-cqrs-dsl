package io.digitalmagic.akka.dsl.context

case class ProgramContext()
object ProgramContext {
  implicit val context: ProgramContext = new ProgramContext
}
case class SerializedProgramContext()
object SerializedProgramContext {
  implicit val serializedContext: SerializedProgramContext = new SerializedProgramContext
}
class ProgramContextOps {
  def emptyContext(name: String): ProgramContext = ProgramContext.context
  def startSubContext(ctx: SerializedProgramContext, name: String): ProgramContext = ProgramContext.context
  def closeContext(ctx: ProgramContext, error: Option[String]): Unit = ()
  def serialize(ctx: ProgramContext): SerializedProgramContext = SerializedProgramContext.serializedContext
}
