package akka.stream.checkpoint

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}

private[checkpoint] final case class CheckpointStage[T](repository: CheckpointRepository, clock: Clock) extends GraphStage[FlowShape[T, T]] {
  val in = Inlet[T]("Checkpoint.in")
  val out = Outlet[T]("Checkpoint.out")
  override val shape = FlowShape(in, out)

  override def initialAttributes: Attributes = Attributes.name("checkpoint")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {

      var lastPulled: Long = 0L
      var lastPushed: Long = 0L

      override def preStart(): Unit = {
        lastPulled = clock.nanoTime
        lastPushed = lastPulled
      }

      override def onPush(): Unit = {
        push(out, grab(in))

        val now = clock.nanoTime
        val lastPushedSpan = now - lastPushed

        if (lastPushedSpan > 0)
          repository.markPush(now - lastPulled, (lastPulled - lastPushed) * 100 / lastPushedSpan)
        else
          // If time cannot be discerned, give a neutral stat so we don't skew the count. There is no right answer.
          repository.markPush(now - lastPulled, 50)

        lastPushed = now
      }

      override def onPull(): Unit = {
        pull(in)

        lastPulled = clock.nanoTime
        repository.markPull(lastPulled - lastPushed)
      }

      override def onUpstreamFinish(): Unit = {
        repository.markCompletion()
        super.onUpstreamFinish()
      }

      override def onUpstreamFailure(ex: Throwable): Unit = {
        repository.markFailure(ex)
        super.onUpstreamFailure(ex)
      }

      override def onDownstreamFinish(): Unit = {
        repository.markCompletion()
        super.onDownstreamFinish()
      }

      setHandlers(in, out, this)
    }
}
