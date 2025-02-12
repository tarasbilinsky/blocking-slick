package com.github.takezoe.slick.blocking

import com.dimafeng.testcontainers.Container
import com.dimafeng.testcontainers.ForAllTestContainer
import com.dimafeng.testcontainers.JdbcDatabaseContainer
import com.dimafeng.testcontainers.MySQLContainer
import org.scalatest.funsuite.AnyFunSuite
import org.testcontainers.utility.DockerImageName
import slick.jdbc.JdbcBackend
import slick.jdbc.meta.MTable
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration

class SlickBlockingAPISpecH2
    extends SlickBlockingAPISpec(
      BlockingH2Driver
    ) {
  protected override val db = Tables.profile.blockingApi.Database.forURL("jdbc:h2:mem:test;TRACE_LEVEL_FILE=4")
}

class SlickBlockingAPISpecMySQL56 extends SlickBlockingAPISpecMySQL("5.6")

abstract class SlickBlockingAPISpecMySQL(mysqlVersion: String)
    extends SlickBlockingAPISpecTestContainer(
      MySQLContainer(mysqlImageVersion = DockerImageName.parse("mysql:" + mysqlVersion)),
      BlockingMySQLDriver
    )

abstract class SlickBlockingAPISpecTestContainer(
  override val container: JdbcDatabaseContainer with Container,
  profile: BlockingJdbcProfile
) extends SlickBlockingAPISpec(profile)
    with ForAllTestContainer {

  override lazy val db = Tables.profile.blockingApi.Database.forURL(
    url = container.jdbcUrl,
    user = container.username,
    password = container.password,
    driver = container.driverClassName
  )

}

abstract class SlickBlockingAPISpec(p: BlockingJdbcProfile) extends AnyFunSuite { self =>
  object Tables extends models.Tables {
    override val profile: BlockingJdbcProfile = self.p
  }
  import Tables.profile.blockingApi._
  import Tables._

  protected val db: Tables.profile.api.Database

  private final def testWithSession[A](f: Tables.profile.blockingApi.Session => A): A = {
    db.withSession { implicit session =>
      try {
        Tables.schema.create
        f(session.asInstanceOf[Session])
      } finally {
        Tables.schema.remove
      }
    }
  }

  test("CRUD operation") {
    testWithSession { implicit session =>
      // Insert
      Users.insert(UsersRow(1, "takezoe", None))
      Users.insert(UsersRow(2, "chibochibo", None))
      Users.insert(UsersRow(3, "tanacasino", None))

      val count1 = Query(Users.length).first
      assert(count1 == 3)

      val result1 = Users.sortBy(_.id).list
      assert(result1.length == 3)
      assert(result1(0) == UsersRow(1, "takezoe", None))
      assert(result1(1) == UsersRow(2, "chibochibo", None))
      assert(result1(2) == UsersRow(3, "tanacasino", None))

      // Update
      Users.filter(_.id === 1L.bind).map(_.name).update("naoki")

      val result2 = Users.filter(_.id === 1L.bind).first
      assert(result2 == UsersRow(1, "naoki", None))

      // Delete
      Users.filter(_.id === 1L.bind).delete

      val result3 = Users.filter(_.id === 1L.bind).firstOption
      assert(result3.isEmpty)

      val count2 = Query(Users.length).first
      assert(count2 == 2)
    }
  }

  test("Plain SQL") {
    testWithSession { implicit session =>
      // plain sql
      val id1 = 1
      val name1 = "takezoe"
      val insert1 = sqlu"INSERT INTO USERS (ID, NAME) VALUES (${id1}, ${name1})"
      insert1.execute

      val query = sql"SELECT COUNT(*) FROM USERS".as[Int]
      val count1 = query.first
      assert(count1 == 1)

      val id2 = 2
      val name2 = "chibochibo"
      val insert2 = sqlu"INSERT INTO USERS (ID, NAME) VALUES (${id2}, ${name2})"
      insert2.execute

      val count2 = query.first
      assert(count2 == 2)
    }
  }

  test("exists") {
    testWithSession { implicit session =>
      val exists1 = Users.filter(_.id === 1L.bind).filter(_.name === "takezoe".bind).exists.run
      assert(exists1 == false)

      Users.insert(UsersRow(1, "takezoe", None))

      val exists2 = Users.filter(_.id === 1L.bind).filter(_.name === "takezoe".bind).exists.run
      assert(exists2 == true)

    }
  }

  test("sum") {
    testWithSession { implicit session =>
      val sum = Users.map(_.id).sum.run
      assert(sum == None)

    }
  }

  test("run") {
    testWithSession { implicit session =>
      assert(Users.run.length == 0)
    }
  }

  test("insertAll") {
    testWithSession { implicit session =>
      val users = List(
        UsersRow(1, "takezoe", None),
        UsersRow(2, "chibochibo", None),
        UsersRow(3, "tanacasino", None)
      )

      Users.insertAll(users: _*)
      val count1 = Query(Users.length).first
      assert(count1 == 3)

      Users ++= users
      val count2 = Query(Users.length).first
      assert(count2 == 6)
    }
  }

  test("insert returning") {
    testWithSession { implicit session =>
      val id = Users.returning(Users.map(_.id)) insert UsersRow(1, "takezoe", None)
      assert(id == 1)
      assert(Users.length.run == 1)
      val u = (Users.returning(Users.map(_.id)).into((u, id) => u.copy(id = id))) insert UsersRow(2, "takezoe", None)
      assert(u.id == 2)
      assert(Users.length.run == 2)
    }

  }

  test("insert multiple returning") {
    testWithSession { implicit session =>
      val id = Users.returning(Users.map(_.id)) insertAll (UsersRow(1, "takezoe", None), UsersRow(2, "mrfyda", None))
      assert(id == List(1, 2))
      assert(Users.length.run == 2)
      val u = (Users.returning(Users.map(_.id)).into((u, id) => u.copy(id = id))) insertAll (UsersRow(
        3,
        "takezoe",
        None
      ), UsersRow(4, "mrfyda", None))
      assert(u.map(_.id) == List(3, 4))
      assert(Users.length.run == 4)
    }
  }

  test("insert insertOrUpdate") {
    testWithSession { implicit session =>
      Users.insertOrUpdate(UsersRow(1, "takezoe", None))
      assert(Users.length.run == 1)
      Users.insertOrUpdate(UsersRow(1, "joao", None))
      assert(Users.length.run == 1)
    }
  }

  test("withTransaction Query") {
    withTransaction(
      u => s => Users.insert(u)(s),
      id => s => Users.filter(_.id === id.bind).exists.run(s)
    )
  }

  test("withTransaction Action") {
    withTransaction(
      u => s => sqlu"insert into USERS values (${u.id}, ${u.name}, ${u.companyId})".execute(s),
      id => s => sql"select exists (select * from USERS where id = $id)".as[Boolean].first(s)
    )
  }

  private def withTransaction(
    insertUser: UsersRow => Session => Int,
    existsUser: Long => Session => Boolean
  ) = {
    testWithSession { implicit session =>
      { // rollback
        session.withTransaction {
          insertUser(UsersRow(1, "takezoe", None))(session)
          val exists = existsUser(1)(session)
          assert(exists == true)
          session.conn.rollback()
        }
        val exists = existsUser(1)(session)
        assert(exists == false)
      }

      { // ok
        session.withTransaction {
          insertUser(UsersRow(2, "takezoe", None))(session)
          val exists = existsUser(2)(session)
          assert(exists == true)
        }
        val exists = existsUser(2)(session)
        assert(exists == true)
      }

      { // nest (rollback)
        session.withTransaction {
          insertUser(UsersRow(3, "takezoe", None))(session)
          assert(existsUser(3)(session) == true)
          session.withTransaction {
            insertUser(UsersRow(4, "takezoe", None))(session)
            assert(existsUser(4)(session) == true)
            session.conn.rollback()
          }
        }
        assert(existsUser(3)(session) == false)
        assert(existsUser(4)(session) == false)
      }

      { // nest (ok)
        session.withTransaction {
          insertUser(UsersRow(5, "takezoe", None))(session)
          assert(existsUser(5)(session) == true)
          session.withTransaction {
            insertUser(UsersRow(6, "takezoe", None))(session)
            assert(existsUser(6)(session) == true)
          }
        }
        assert(existsUser(5)(session) == true)
        assert(existsUser(6)(session) == true)
      }
    }
  }

  test("MTable support") {
    if (this.isInstanceOf[SlickBlockingAPISpecH2]) {
      testWithSession { implicit session =>
        assert(MTable.getTables.list.length == 2)
      }
    } else {
      pending // TODO
    }
  }

  test("Transaction support with Query SELECT FOR UPDATE") {
    testTransactionWithSelectForUpdate { implicit session =>
      Users.map(_.id).forUpdate.list
    }
  }

  test("Transaction support with Action SELECT FOR UPDATE") {
    testTransactionWithSelectForUpdate { implicit session =>
      sql"select id from USERS for update".as[Long].list
    }
  }

  private def testTransactionWithSelectForUpdate(selectForUpdate: Session => Seq[Long]) = {
    import scala.concurrent.ExecutionContext.Implicits.global
    if (this.isInstanceOf[SlickBlockingAPISpecH2]) {
      testWithSession { implicit session =>
        // Insert
        Users.insert(UsersRow(1, "takezoe", None))

        // concurrently do a select for update
        val f1 = Future {
          db.withTransaction { implicit session =>
            val l = selectForUpdate(session.asInstanceOf[Session]).length
            // default h2 lock timeout is 1000ms
            Thread.sleep(3000L)
            l
          }
        }

        // and try to update a row
        val f2 = Future {
          db.withTransaction { implicit session =>
            Thread.sleep(500L)
            Users.filter(_.id === 1L).map(_.name).update("João")
          }
        }

        assert(Await.result(f1, Duration.Inf) == 1)
        assertThrows[Exception](Await.result(f2, Duration.Inf))
      }
    } else {
      pending // TODO
    }
  }

  test("compiled support") {
    if (this.isInstanceOf[SlickBlockingAPISpecH2]) {
      testWithSession { implicit session =>
        val compiled = Compiled { (i: Rep[Long]) => Users.filter(_.id === i) }
        assert(compiled(1L).run.length === 0)

        // Insert
        val insertCompiled = Users.insertInvoker
        insertCompiled.insert(UsersRow(1, "takezoe", None))
        assert(compiled(1L).run.length === 1)

        // update
        val compiledUpdate = Compiled { (n: Rep[String]) => Users.filter(_.name === n).map(_.name) }
        compiledUpdate("takezoe").update("João")

        // delete
        compiledUpdate("João").delete

        assert(compiled(1L).run.length === 0)
      }
    } else {
      pending // TODO
    }
  }

  test("Plain SQL chained together") {
    testWithSession { implicit session =>
      implicit val ctx = ExecutionContext.global

      // plain sql
      val id1 = 1
      val id2 = 2
      val name1 = "takezoe"
      val name2 = "chibochibo"
      val insert1 = sqlu"INSERT INTO USERS (ID, NAME) VALUES (${id1}, ${name1})" andThen
        sqlu"INSERT INTO USERS (ID, NAME) VALUES (${id2}, ${name2})"
      insert1.run

      val query = for {
        count <- sql"SELECT COUNT(*) FROM USERS".as[Int].head
        max <- sql"SELECT MAX(ID) FROM USERS".as[Int].head
      } yield (count, max)
      val (count1, max1) = query.run
      assert(count1 == 2)
      assert(max1 == 2)

      val id3 = 3
      val name3 = "drapp"
      val insert2 = sqlu"INSERT INTO USERS (ID, NAME) VALUES (${id3}, ${name3})" andThen
        sqlu"DELETE FROM USERS WHERE ID=${id1}"
      insert2.run

      val count2 = query.run
      assert(count2 == (2, 3))

    }
  }

  test("DBIO.sequence") {
    testWithSession { implicit session =>
      implicit val ctx = ExecutionContext.global

      val users = (1 to 3).map(i => UsersRow(i, i.toString, None))

      val dbios = users.map(u => Users.forceInsert(u))
      val dbioSequence = DBIO.sequence(dbios)

      dbioSequence.run

      val count1 = Query(Users.length).first
      assert(count1 == 3)
    }
  }
}
