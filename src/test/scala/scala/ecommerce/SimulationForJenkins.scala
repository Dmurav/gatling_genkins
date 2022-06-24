package scala.ecommerce

import scala.concurrent.duration._
import io.gatling.core.Predef._
import io.gatling.core.feeder.BatchableFeederBuilder
import io.gatling.http.Predef._
import io.gatling.jdbc.Predef._

import javax.jms.Session
import scala.util.Random

class SimulationForJenkins extends Simulation {

  val domain = "demostore.gatling.io"
  val categoryFeeder = csv("data/categoryDetails.csv").random
  val jsonFeeder = jsonFile("data/productDetails.json").random
  val csvLoginFeeder = csv("data/loginDetails.csv").circular

  val rnd = new Random()

  def randomString(length:Int):String = {
    rnd.alphanumeric.filter(_.isLetter).take(length).mkString
  }

  val initSession = exec(flushCookieJar)
    .exec(session => session.set("randomNumber", rnd.nextInt))
    .exec(session => session.set("customerLoggedIn", false))
    .exec(session => session.set("cartTotal", 0.00))
    .exec(addCookie(Cookie("sessionId", randomString(10)).withDomain(domain)))
    //.exec { session => println(session); session}

  private val httpProtocol = http
    .baseUrl("http://" + domain)

  object Pages {
    def homePage = {
      exec(
        http("home page")
          .get("/")
          .check(status.is(200))
          .check(css("#_csrf", "content").saveAs("csrfValue"))
          .check(regex("<title>Gatling Demo-Store</title>").exists)
      )
    }

    def aboutUs = {
      exec(
        http("about us")
          .get("/about-us")
          .check(status.is(200))
          .check(regex("<p>This is a fictional"))
      )
    }
  }

  object Catalog {

    object Categories {

      def view = {
        feed(categoryFeeder)
        .exec(
          http("load page - #{categoryName}")
            .get("/category/#{categorySlug}")
            .check(status.is(200))
            .check(css("#CategoryName").is("#{categoryName}"))
        )
      }
    }

    object Product {

      def view = {
        feed(jsonFeeder)
          .exec(
            http("load product page - #{name}")
              .get("/product/#{slug}")
              .check(status.is(200))
              .check(css("#ProductDescription").is("#{description}"))
          )
      }

      def add = {
        repeat(3, "i") {
          exec(view)
            .exec(
              http("add product to cart")
                .get("/cart/add/#{id}")
                .check(status.is(200))
                .check(substring("items in your cart"))
            )
            .exec(session => {
              val currentCartTotal = session("cartTotal").as[Double]
              val itemPrice = session("price").as[Double]
              session.set("cartTotal", (currentCartTotal + itemPrice))
            }
            )
        }
      }
    }
  }

  object Customer {

    def login = {
      feed(csvLoginFeeder)
        .exec(
          http("login page")
            .get("/login")
            .check(status.is(200))
            .check(substring("Username:"))
        )
        //.exec { session => println(session); session} // для дебага
        .exec(
          http("login action")
            .post("/login")
            .formParam("_csrf", "#{csrfValue}")
            .formParam("username", "#{username}")
            .formParam("password", "#{password}")
            .check(css("#_csrf", "content").saveAs("newCsrfValue"))
            .check(status.is(200))
        )
        .exec(session => session.set("customerLoggedIn", true))
       // .exec { session => println(session); session} // для дебага
    }

    def logout = {
      exec(
        http("logout")
          .post("/logout")
          .formParam("_csrf", "#{newCsrfValue}")
          .check(status.is(200))
      )
        .exec(session => session.set("customerLoggedIn", false))
    }
  }

  object Checkout {

    def viewCart = {
      doIf(session => !session("customerLoggedIn").as[Boolean]) {
        exec(Customer.login)
      }
      .exec(
        http("view cart")
          .get("/cart/view")
          .check(status.is(200))
          .check(css("#_csrf", "content").saveAs("newCsrfValue"))
          .check(css("#grandTotal").is("$#{cartTotal}"))
      )
       // .exec { session => println(session); session} // для дебага
    }

    def checkout = {
      exec(
        http("checkout cart")
          .get("/cart/checkout")
          .check(status.is(200))
          .check(substring("Thanks for your order! See you soon!"))
      )
    }
  }

  private val scn = scenario("ecomerceSimulation")
    .exec(initSession)
    .exec(Pages.homePage)
    .pause(1)
    .exec(Pages.aboutUs)
    .pause(1)
    .exec(Catalog.Product.add)
    .pause(1)
    .exec(Checkout.viewCart)
    .pause(1)
    .exec(Checkout.checkout)
    .pause(1)
    .exec(Customer.logout)

	setUp(
    scn.inject(
      atOnceUsers(3),
      nothingFor(5),
      rampUsers(10).during(5),
      nothingFor(10),
      constantUsersPerSec(1).during(10)
    )
  ).protocols(httpProtocol)
}
