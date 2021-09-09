package io.digitalmagic.akka.dsl.context

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.{Span, StatusCode, Tracer}
import io.opentelemetry.context.propagation.{TextMapGetter, TextMapSetter}
import io.opentelemetry.context.{Context, Scope}

import scala.collection.mutable

case class ProgramContext(span: Span, scope: Scope)

case class SerializedProgramContext(ctxMap: Map[String, String])
object SerializedProgramContext {
  val none: SerializedProgramContext = SerializedProgramContext(Map.empty)
  implicit def contextToSerializedContext(implicit context: ProgramContext, telemetrySdk: OpenTelemetry): SerializedProgramContext = ProgramContextOps.getTracePropagationCtx(context.span)
  implicit def spanToSerializedContext(implicit span: Span, telemetrySdk: OpenTelemetry): SerializedProgramContext = ProgramContextOps.getTracePropagationCtx(span)
}

object ProgramContextOps {
  def getTracePropagationCtx(span: Span)(implicit telemetrySdk: OpenTelemetry): SerializedProgramContext = {
    val context = if (Span.getInvalid == span) Context.root else Context.current().`with`(span)
    getTracePropagationCtx(context)
  }

  def getTracePropagationCtx(traceCtx: Context)(implicit telemetrySdk: OpenTelemetry): SerializedProgramContext = {
    val mutMap = scala.collection.mutable.Map[String, String]()
    telemetrySdk.getPropagators.getTextMapPropagator.inject(traceCtx, mutMap, mapTraceContextSetter)
    SerializedProgramContext(mutMap.toMap)
  }

  val mapTraceContextSetter: TextMapSetter[mutable.Map[String, String]] = new TextMapSetter[mutable.Map[String, String]](){
    def set(carrier: mutable.Map[String, String], key: String, value: String): Unit = {
      carrier.put(key, value)
    }
  }

  val mapTraceContextGetter: TextMapGetter[Map[String, String]] = new TextMapGetter[Map[String, String]]() {
    import scala.collection.JavaConverters._
    @Override
    def keys(carrier: Map[String, String]): java.lang.Iterable[String] = carrier.keys.asJava
    @Override
    def get(carrier: Map[String, String], key: String): String = carrier.get(key) match {
      case Some(v) => v
      case _       => null
    }
  }
}

class ProgramContextOps(implicit tracer: Tracer, telemetrySdk: OpenTelemetry) {
  import ProgramContextOps._

  def emptyContext(name: String): ProgramContext = startSubContext(SerializedProgramContext.none, name)
  def startSubContext(ctx: SerializedProgramContext, name: String): ProgramContext = {
    val parentContext = ctx.ctxMap match {
      case m if m.isEmpty =>
        Context.root()
      case ctxMap =>
        telemetrySdk.getPropagators.getTextMapPropagator.extract(Context.current, ctxMap, mapTraceContextGetter)
    }
    val spanBuilder = tracer.spanBuilder(name).setParent(parentContext)
    val span = spanBuilder.startSpan()
    val scope = span.makeCurrent()
    ProgramContext(span, scope)
  }
  def closeContext(ctx: ProgramContext, error: Option[String]): Unit = {
    error match {
      case Some(err) => ctx.span.setStatus(StatusCode.ERROR, err)
      case None      => ctx.span.setStatus(StatusCode.OK)
    }
    ctx.scope.close()
    ctx.span.end()
  }
  def serialize(ctx: ProgramContext): SerializedProgramContext = getTracePropagationCtx(ctx.span)
}
