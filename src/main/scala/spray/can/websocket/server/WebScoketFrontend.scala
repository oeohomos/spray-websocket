package spray.can.websocket.server

/**
 * This pipeline stage simply forwards the events to and receives commands from
 * the given MessageHandler. It is the final stage of the websocket pipeline,
 * and is how the pipeline interacts with user code.
 *
 * @param handler the actor which will receive the incoming Frames
 */
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.io.Tcp
import spray.can.server.ServerSettings
import spray.can.server.UHttp
import spray.can.websocket.{ FrameStreamCommand, FrameOutEvent, FrameCommand, FrameInEvent }
import spray.can.websocket.frame.{ FrameStream, Frame, CloseFrame, PingFrame, ContinuationFrame }
import spray.io.Pipeline
import spray.io.PipelineContext
import spray.io.Pipelines
import spray.io.RawPipelineStage
import spray.can.client.ClientConnectionSettings
import spray.can.Http
import spray.http.HttpResponse

object WebSocketFrontend {

  def apply(settings: ServerSettings, handler: ActorRef) = new RawPipelineStage[PipelineContext] {
    def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines = new Pipelines {
      /**
       * proxy actor between handler and pipelines owner. It behaves as the sender
       * (instead of pipelines owner actor) which is telling handler:
       *
       *   HttpServerConnection(pipelines' owner) <-> receiverRef <-> handler
       *
       *
       *   +-------------------+  Frame Out     +---------+
       *   |                   | <-----------   |         |
       *   | WebSocketFrontend |                | Handler |
       *   |  (receiverRef)    | ----------->   |         |
       *   |-------------------|  Frame In      +---------+
       *   |  v             ^  |
       *   |  v             ^  |
       *   |-------------------|
       *   |                   |
       *   |                   |
       *   |      TCPIO        |
       *   +-------------------+
       *            ^
       *            |
       *            v
       *     +--------------+
       *     | client       |
       *     +--------------+
       *
       */
      private val actorContext = context.actorContext
      private val receiverRef = actorContext.actorOf(Props(new HandlerResponseReceiver), name = "websocket_receiver")

      val commandPipeline = commandPL

      val eventPipeline: EPL = {
        case FrameOutEvent(frame)                   => commandPL(FrameCommand(frame))

        case FrameInEvent(frame: CloseFrame)        => commandPL(FrameCommand(frame))
        case FrameInEvent(frame: PingFrame)         => // We'll auto pong it, does not need to tell handler
        case FrameInEvent(frame: ContinuationFrame) => // We should have composed it during lower stage. Anyway, does not need to tell handler
        case FrameInEvent(frame)                    => commandPL(Pipeline.Tell(handler, frame, receiverRef))

        case ev: UHttp.Upgraded                     => commandPL(Pipeline.Tell(handler, ev, receiverRef))
        case ev: Tcp.ConnectionClosed               => commandPL(Pipeline.Tell(handler, ev, receiverRef))
        case Http.MessageEvent(resp: HttpResponse)  => commandPL(Pipeline.Tell(handler, resp, receiverRef))

        case ev                                     => eventPL(ev)
      }

      /**
       * Receive handler's sending and wrap to Command, then put on the head of
       * context.actorContext.self's pipelines
       * TODO implement it as UnregisteredActorRef?
       */
      class HandlerResponseReceiver extends Actor {
        def receive = {
          case x: Frame       => actorContext.self ! FrameCommand(x)
          case x: FrameStream => actorContext.self ! FrameStreamCommand(x)
          case Tcp.Close      => actorContext.self ! Tcp.Close
        }
      }

    }

  }

  def apply(settings: ClientConnectionSettings, handler: ActorRef): RawPipelineStage[PipelineContext] = apply(null: ServerSettings, handler)

}