package akka_streams
import akka.NotUsed
import akka.actor.typed.{ActorSystem, Behavior, Props}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.stream.ClosedShape
import akka.stream.alpakka.slick.scaladsl.{Slick, SlickSession}
import akka.stream.scaladsl.GraphDSL.Implicits.{SourceShapeArrow, port2flow}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, RunnableGraph, Sink, Source, Zip}
import akka_typed.TypedCalculatorWriteSide.{Add, Added, Command, Divide, Divided, Multiplied, Multiply}
import scalikejdbc.DB.using
import slick.jdbc.H2Profile.api._
import scalikejdbc.{ConnectionPool, ConnectionPoolSettings, DB}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

object  akka_typed{

  trait CborSerialization

  val persId = PersistenceId.ofUniqueId("001")

  object TypedCalculatorWriteSide{
    sealed trait Command
    case class Add(amount: Int) extends Command
    case class Multiply(amount: Int) extends Command
    case class Divide(amount: Int) extends Command

    sealed trait Event
    case class Added(id:Int, amount: Int) extends Event
    case class Multiplied(id:Int, amount: Int) extends Event
    case class Divided(id:Int, amount: Int) extends Event

    final case class State(value:Int) extends CborSerialization
    {
      def add(amount: Int): State = copy(value = value + amount)
      def multiply(amount: Int): State = copy(value = value * amount)
      def divide(amount: Int): State = copy(value = value / amount)
    }

    object State{
      val empty = State(0)
    }


    def handleCommand(
                     persistenceId: String,
                     state: State,
                     command: Command,
                     ctx: ActorContext[Command]
                     ): Effect[Event, State] =
      command match {
        case Add(amount) =>
          ctx.log.info(s"receive adding  for number: $amount and state is ${state.value}")
          val added = Added(persistenceId.toInt, amount)
          Effect
          .persist(added)
          .thenRun{
            x=> ctx.log.info(s"The state result is ${x.value}")
          }
        case Multiply(amount) =>
          ctx.log.info(s"receive multiplying  for number: $amount and state is ${state.value}")
          val multiplied = Multiplied(persistenceId.toInt, amount)
          Effect
            .persist(multiplied)
            .thenRun{
              x=> ctx.log.info(s"The state result is ${x.value}")
            }
        case Divide(amount) =>
          ctx.log.info(s"receive dividing  for number: $amount and state is ${state.value}")
          val divided = Divided(persistenceId.toInt, amount)
          Effect
            .persist(divided)
            .thenRun{
              x=> ctx.log.info(s"The state result is ${x.value}")
            }
      }

    def handleEvent(state: State, event: Event, ctx: ActorContext[Command]): State =
      event match {
        case Added(_, amount) =>
          ctx.log.info(s"Handling event Added is: $amount and state is ${state.value}")
          state.add(amount)
        case Multiplied(_, amount) =>
          ctx.log.info(s"Handling event Multiplied is: $amount and state is ${state.value}")
          state.multiply(amount)
        case Divided(_, amount) =>
          ctx.log.info(s"Handling event Divided is: $amount and state is ${state.value}")
          state.divide(amount)
      }

    def apply(): Behavior[Command] =
      Behaviors.setup{ ctx =>
        EventSourcedBehavior[Command, Event, State](
          persistenceId = persId,
          State.empty,
          (state, command) => handleCommand("001", state, command, ctx),
          (state, event) => handleEvent(state, event, ctx)
        )
      }

  }


  case class TypedCalculatorReadSide(system: ActorSystem[NotUsed]){
//    initDatabase
    implicit val session: SlickSession = SlickSession.forConfig("slick-postgres")
    implicit val materializer = system.classicSystem
    var (offset, latestCalculatedResult) = getLatestsOffsetAndResult
    val startOffset: Int = if (offset == 1) 1 else offset + 1

    val readJournal = PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

    def getLatestsOffsetAndResult: (Int, Double) = Await.result(
      session.db.run(
        sql"select write_side_offset, calculated_value from public.result where id = 1".as[(Int, Double)]
      ),
      20.seconds
    ).head
    /*
    /**
     * В read side приложения с архитектурой CQRS (объект TypedCalculatorReadSide в TypedCalculatorReadAndWriteSide.scala) необходимо разделить бизнес логику и запись в целевой получатель, т.е.
     * 1) Persistence Query должно находиться в Source
     * 2) Обновление состояния необходимо переместить в отдельный от записи в БД флоу
     * 3) ! Задание со звездочкой: вместо CalculatorRepository создать Sink c любой БД (например Postgres из docker-compose файла).
     * Для последнего задания пригодится документация - https://doc.akka.io/docs/alpakka/current/slick.html#using-a-slick-flow-or-sink
     * Результат выполненного д.з. необходимо оформить либо на github gist либо PR к текущему репозиторию.
     *
     * */

    как делать:
    1. в типах int заменить на double
    2. изменения в строках 125-148
    3. добавить функцию updateState в которой будет паттерн матчинг событий Added Multiplied Divided
    4.создаете graphDsl  в котором: builder.add(source)
    5. builder.add(Flow[EventEnvelope].map( e => updateState(e.event, e.seqNr)))
     */


    val source: Source[EventEnvelope, NotUsed] = readJournal.eventsByPersistenceId("001", startOffset, Long.MaxValue)

  // momework, spoiler
    def updateState: Flow[EventEnvelope,EventEnvelope, NotUsed] = Flow[EventEnvelope].map{
      event => event.event
      match {
        case Added(_, amount) =>
          latestCalculatedResult += amount
        case Multiplied(_, amount) =>
          latestCalculatedResult *= amount
        case Divided(_, amount) =>
          latestCalculatedResult /= amount
      }
      event
    }

    def writeState: Flow[EventEnvelope, Int, NotUsed] = Slick.flow(event =>
    sqlu"UPDATE public.result SET calculated_value=${latestCalculatedResult}, wrie_side_offset=${event.sequenceNr} WHERE ID=1"
    )
//
    val runnableGraph: RunnableGraph[NotUsed]=
      source.async.
        via(updateState).async
        .via(writeState).async
        .to(Sink.ignore)
    runnableGraph.run()


//        val graph = GraphDSL.create(){
//          implicit builder: GraphDSL.Builder[NotUsed] =>
//            //1.
//            val input = builder.add(source)
//            val stateUpdater = builder.add(updateState)
//            val localSaveOutput = builder.add(Sink.foreach[Result]{
//              r=>
//                latestCalculatedResult = r.state
//                println("something to print")
//            })
//
//            val dbSaveOutput = builder.add(
//              Sink.foreach[Result] {
//                r=> updatedResultAndOffset(r.state,r.offset)
//              }
//
//            )
//            val output = updateState
//            val broadcast = builder.add(Broadcast[Int](2))
//            val zip = builder.add(Zip[Int, Int])
//            input ~> broadcast
//            broadcast.out(0) ~> localSaveOutput
//            broadcast.out(1) ~> dbSaveOutput
//
//            zip.out ~> output
//            ClosedShape
//
//

//        }

  }

  //









  def apply(): Behavior[NotUsed] =
    Behaviors.setup{
      ctx =>
        val writeAcorRef = ctx.spawn(TypedCalculatorWriteSide(), "Calc", Props.empty)
        writeAcorRef ! Add(10)
        writeAcorRef ! Multiply(2)
        writeAcorRef ! Divide(5)

        Behaviors.same
    }

  def execute(command: Command): Behavior[NotUsed] =
    Behaviors.setup{ ctx =>
      val writeAcorRef = ctx.spawn(TypedCalculatorWriteSide(), "Calc", Props.empty)
      writeAcorRef ! command
      Behaviors.same
    }

  def main(args: Array[String]): Unit = {
    val value = akka_typed()
    implicit  val system: ActorSystem[NotUsed] = ActorSystem(value, "akka_typed")

    TypedCalculatorReadSide(system)
    implicit val executionContext = system.executionContext
  }

}