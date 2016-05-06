package edu.uci.ics.cloudberry.zion.actor

import akka.actor.{Actor, ActorRef, Props, Stash}
import edu.uci.ics.cloudberry.zion.actor.ViewActor.DoneInitializing
import edu.uci.ics.cloudberry.zion.model._
import org.joda.time.DateTime
import play.api.libs.json._

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}


// One ViewManager per store
abstract class ViewsManagerActor(val sourceName: String, val sourceActor: ActorRef)(implicit ec: ExecutionContext)
  extends Actor with Stash {

  protected val viewMeta = mutable.Map.empty[String, ViewMetaRecord]

  def getViewKey(query: DBQuery): String

  def createViewStore(query: DBQuery): Future[ViewMetaRecord]

  def createViewActor(query: DBQuery, fView: Future[ViewMetaRecord]): Actor

  def loadMetaStore: Future[Seq[ViewMetaRecord]]

  def flushMeta()

  def flushInterval: FiniteDuration

  override def preStart(): Unit = {
    loadMetaStore.onComplete {
      case Success(seq) => viewMeta ++= seq.map(r => r.viewKey -> r); self ! DoneInitializing
      case Failure(t) => "error happens: " + t.getMessage
    }
  }

  import ViewsManagerActor._

  override def receive: Receive = initializing

  private def initializing: Receive = {
    case DoneInitializing =>
      unstashAll()
      context.become(routine)
    case msg => stash()
  }

  private def routine: Receive = {
    case ViewsManagerActor.flushMetaMsg => flushMeta()
    case query: DBQuery => {
      //TODO a little awkward implementation. we need the key before the view
      val key = getViewKey(query)
      context.child(key).getOrElse {
        val viewRecord = viewMeta.get(key)
        val fView = if (viewRecord.isEmpty) {
          createViewStore(query)
        } else {
          Future(viewRecord.get)
        }
        context.actorOf(Props(createViewActor(query, fView)), key)
      } forward (query)
    }
    case (key: String, viewRecord: ViewMetaRecord) => {
      if (viewMeta.get(key).isDefined){
        viewMeta.update(key, viewRecord)
      } else {
        viewMeta.update(key, viewRecord)
        self ! ViewsManagerActor.flushMetaMsg
      }
    }
  }


  // flush to persistent storage every 30 minutes.
  context.system.scheduler.schedule(flushInterval, flushInterval, self, flushMetaMsg)

  override def postStop(): Unit = flushMeta()
}


object ViewsManagerActor {

  object flushMetaMsg

}

case class ViewMetaRecord(sourceName: String,
                          viewKey: String,
                          summaryLevel: SummaryLevel,
                          //queryTemplate: DBQuery,
                          startTime: DateTime,
                          lastVisitTime: DateTime,
                          lastUpdateTime: DateTime,
                          visitTimes: Int,
                          updateCycle: Duration
                         )

object ViewMetaRecord {
  val timeFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
  implicit val jodaTimeReaders: Reads[DateTime] = Reads.jodaDateReads(timeFormat)
  implicit val jodaTimeWriters: Writes[DateTime] = Writes.jodaDateWrites(timeFormat)

  implicit val summaryLevelFormat: Format[SummaryLevel] = {
    new Format[SummaryLevel] {
      val spatialKey = "spatialLevel"
      val timeKey = "timeLevel"

      override def writes(level: SummaryLevel): JsValue = {
        JsObject(Seq(spatialKey -> JsNumber(level.spatialLevel.id), timeKey -> JsNumber(level.timeLevel.id)))
      }

      override def reads(json: JsValue): JsResult[SummaryLevel] = {
        JsSuccess {
          SummaryLevel(SpatialLevels((json \ spatialKey).as[Int]), TimeLevels((json \ timeKey).as[Int]))
        }
      }
    }
  }

  implicit val durationFormat: Format[Duration] = {
    new Format[Duration] {

      import scala.concurrent.duration._

      override def writes(o: Duration): JsValue = JsNumber(o.toSeconds)

      override def reads(json: JsValue): JsResult[Duration] = JsSuccess(Duration(json.as[Long], SECONDS))
    }
  }

  implicit val queryFormat: Format[DBQuery] = {
    new Format[DBQuery] {
      override def writes(o: DBQuery): JsValue = ???

      override def reads(json: JsValue): JsResult[DBQuery] = ???
    }
  }

  implicit val formatter: Format[ViewMetaRecord] = Json.format[ViewMetaRecord]
}









