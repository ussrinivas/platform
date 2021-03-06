/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.ingest
package service

import util._

import com.precog.common.Path
import com.precog.common.accounts._
import com.precog.common.client._
import com.precog.common.ingest._
import com.precog.common.jobs
import com.precog.common.jobs._
import com.precog.common.security._
import com.precog.util.PrecogUnit
import IngestProcessing._

import akka.dispatch.{ExecutionContext, Future, Promise}
import akka.util.Timeout

import blueeyes.core.data.ByteChunk
import blueeyes.core.http._
import blueeyes.core.http.HttpStatusCodes._
import blueeyes.core.http.HttpHeaders._
import blueeyes.core.service._
import blueeyes.json._
import blueeyes.util.Clock

import com.google.common.base.Charsets

import java.io._
import java.nio.ByteBuffer
import java.util.concurrent.{ Executor, RejectedExecutionException }

import com.weiglewilczek.slf4s.Logging

import scalaz._
import scalaz.EitherT._
import scalaz.Validation._
import scalaz.NonEmptyList._
import scalaz.std.function._
import scalaz.std.option._
import scalaz.syntax.id._
import scalaz.syntax.arrow._
import scalaz.syntax.monad._
import scalaz.syntax.show._
import scalaz.syntax.traverse._
import scalaz.syntax.std.boolean._
import scalaz.syntax.std.option._
import scala.annotation.tailrec


sealed trait IngestStore {
  def store(apiKey: APIKey, path: Path, authorities: Authorities, data: Seq[JValue], jobId: Option[JobId], streamRef: StreamRef): Future[StoreFailure \/ PrecogUnit]
}

sealed trait ParseDirective {
  def toMap: Map[String, String] // escape hatch for interacting with other systems
}

class IngestServiceHandler(
  val permissionsFinder: PermissionsFinder[Future],
  jobManager: JobManager[Response],
  val clock: Clock,
  eventStore: EventStore[Future],
  ingestTimeout: Timeout,
  batchSize: Int,
  maxFields: Int,
  ingestTmpDir: File,
  postMode: WriteMode)(implicit val M: Monad[Future], executor: ExecutionContext)
    extends CustomHttpService[ByteChunk, (APIKey, Path) => Future[HttpResponse[JValue]]]
    with IngestSupport with Logging {

  object ingestStore extends IngestStore {
    def store(apiKey: APIKey, path: Path, authorities: Authorities, data: Seq[JValue], jobId: Option[JobId], streamRef: StreamRef): Future[StoreFailure \/ PrecogUnit] = {
      val eventInstance = Ingest(apiKey, path, Some(authorities), data, jobId, clock.instant(), streamRef)
      logger.trace("Saving event: " + eventInstance)
      eventStore.save(eventInstance, ingestTimeout)
    }
  }

  private[this] val processingSelectors = new DefaultIngestProcessingSelectors(maxFields, batchSize, ingestTmpDir, ingestStore)

  def chooseProcessing(apiKey: APIKey, path: Path, authorities: Authorities, request: HttpRequest[ByteChunk]): Future[Option[IngestProcessing]] = {
    val selectors = processingSelectors.selectors(apiKey, path, authorities)

    request.content traverse {
      case Left(bytes) =>
        Promise successful IngestProcessing.select(selectors, bytes, request)

      case Right(stream) =>
        stream.headOption map {
          _ flatMap { bytes => IngestProcessing.select(selectors, bytes, request) }
        }
    } map {
      _.join
    }
  }

  def ingestBatch(apiKey: APIKey, path: Path, authorities: Authorities, request: HttpRequest[ByteChunk], durability: Durability, errorHandling: ErrorHandling, storeMode: WriteMode): EitherT[Future, NonEmptyList[String], IngestResult] =
    right(chooseProcessing(apiKey, path, authorities, request)) flatMap {
      case Some(processing) =>
        EitherT {
          (processing.forRequest(request) tuple request.content.toSuccess(nels("Ingest request missing body content."))) traverse {
            case (processor, data) => processor.ingest(durability, errorHandling, storeMode, data)
          } map {
            _.disjunction
          }
        }

      case None =>
        right(Promise successful NotIngested("Could not determine a data type for your batch ingest. Please set the Content-Type header."))
    }


  def notifyJob(durability: Durability, channel: String, message: String): EitherT[Future, String, PrecogUnit] =
    durability match {
      case GlobalDurability(jobId) =>
        jobManager.addMessage(jobId, channel, JString(message)).map(_ => PrecogUnit)
      case LocalDurability =>
        right(Promise successful PrecogUnit)
    }

  private def jobErrorResponse(message: String) = {
    logger.error("Internal error during ingest; got bad response from the jobs server: " + message)
    HttpResponse(InternalServerError, content = Some(JString("Internal error from job service: " + message)))
  }

  val service: HttpRequest[ByteChunk] => Validation[NotServed, (APIKey, Path) => Future[HttpResponse[JValue]]] = (request: HttpRequest[ByteChunk]) => {
    logger.debug("Got request in ingest handler: " + request)
    Success { (apiKey: APIKey, path: Path) => {
      val timestamp = clock.now()
      def createJob: EitherT[Future, String, JobId] = jobManager.createJob(apiKey, "ingest-" + path, "ingest", None, Some(timestamp)).map(_.id)

      findRequestWriteAuthorities(request, apiKey, path, Some(timestamp.toInstant)) { authorities =>
        logger.debug("Write permission granted for " + authorities + " to " + path)
        request.content map { content =>
          import MimeTypes._
          import Validation._

          val errorHandling = if (request.parameters.get('mode).exists(_ equalsIgnoreCase "batch")) IngestAllPossible
                              else StopOnFirstError

          val durabilityM = request.method match {
            case HttpMethods.POST => createJob map { jobId => (GlobalDurability(jobId), postMode) }
            case HttpMethods.PUT => createJob map { jobId => (GlobalDurability(jobId), AccessMode.Replace) }
            case HttpMethods.PATCH => right[Future, String, (Durability, WriteMode)](Promise.successful((LocalDurability, AccessMode.Append)))
            case _ => left[Future, String, (Durability, WriteMode)](Promise.successful("HTTP method " + request.method + " not supported for data ingest."))
          }

          durabilityM flatMap { case (durability, storeMode) =>
            ingestBatch(apiKey, path, authorities, request, durability, errorHandling, storeMode) flatMap {
              case NotIngested(reason) =>
                val message = "Ingest to %s by %s failed with reason: %s ".format(path, apiKey, reason)
                logger.warn(message)
                notifyJob(durability, JobManager.channels.Warning, message) map { _ =>
                  HttpResponse[JValue](BadRequest, content = Some(JObject("errors" -> JArray(JString(reason)))))
                }

              case StreamingResult(ingested, None) =>
                val message = "Ingest to %s by %s succeeded (%d records)".format(path, apiKey, ingested)
                logger.info(message)
                notifyJob(durability, JobManager.channels.Info, message) map { _ =>
                  val responseContent = JObject("ingested" -> JNum(ingested), "errors" -> JArray())
                  HttpResponse[JValue](OK, content = Some(responseContent))
                }

              case StreamingResult(ingested, Some(error)) =>
                val message = "Ingest to %s by %s failed after %d records with error %s".format(path, apiKey, ingested, error)
                logger.error(message)
                notifyJob(durability, JobManager.channels.Error, message) map { _ =>
                  val responseContent = JObject("ingested" -> JNum(ingested), "errors" -> JArray(JString(error)))
                  HttpResponse[JValue](UnprocessableEntity, content = Some(responseContent))
                }

              case BatchResult(total, ingested, errs) =>
                val failed = errs.size
                val responseContent = JObject(
                  "total" -> JNum(total),
                  "ingested" -> JNum(ingested),
                  "failed" -> JNum(failed),
                  "skipped" -> JNum(total - ingested - failed),
                  "errors" -> JArray(errs map { case (line, msg) => JObject("line" -> JNum(line), "reason" -> JString(msg)) }: _*),
                  "ingestId" -> durability.jobId.map(JString(_)).getOrElse(JUndefined)
                )

                val message = "Ingest to %s with %s succeeded. Result: %s".format(path, apiKey, responseContent.renderPretty)
                logger.info(message)
                notifyJob(durability, JobManager.channels.Info, message) map { _ =>
                  HttpResponse[JValue](if (ingested == 0 && total > 0) BadRequest else OK, content = Some(responseContent))
                }
            }
          } valueOr { errors =>
            HttpResponse(BadRequest, content = Some(JString("Errors were encountered processing your ingest request: " + errors)))
          }
        } getOrElse {
          logger.warn("No event data found for ingest request from %s owner %s at path %s".format(apiKey, authorities, path))
          M.point(HttpResponse[JValue](BadRequest, content = Some(JString("Missing event data."))))
        }
      }
    }}
  }

  val metadata = {
    import MimeTypes._
    import Metadata._
    and(
      about(
        or(requestHeader(`Content-Type`(application/json)), requestHeader(`Content-Type`(text/csv))),
        description("The content type of the ingested data should be specified. The service will attempt to infer structure if no content type is specified, but this may yield degraded or incorrect results under some circumstances.")
      ),
      description("""This service can be used to store one or more records, supplied as either whitespace-delimited JSON or CSV.""")
    )
  }
}
