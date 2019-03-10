package usbuildings

import java.net.URI

import com.monovore.decline.{CommandApp, Opts}
import geotrellis.spark.io.kryo.KryoRegistrator
import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.sql.SparkSession
import org.apache.spark.{SparkConf, SparkContext}
import cats.implicits._

import scala.util.Properties

object Main extends CommandApp(
  name = "geotrellis-usbuildings",
  header = "Collect building footprint elevations from terrain tiles",
  main = {
    val buildingsAllOpt = Opts.flag(long = "all-buildings", help = "Read all building footprints from hosted source.").orFalse
    val buildingsOpt = Opts.option[String]("buildings", help = "URI of the building shapes layers")
    val outputOpt = Opts.option[String]("output", help = "S3 URI prefix of output tiles").withDefault("s3://geotrellis-test/usbuildings/default")
    val layersOpt = Opts.options[String]("layer", help = "Layer to read exclusively, empty for all layers").orEmpty

    (buildingsAllOpt, buildingsOpt, outputOpt, layersOpt).mapN { (buildingsAll, buildingsUri, outputUriString, layers) =>
      val outputUri = new URI(outputUriString)
      if (outputUri.getScheme != "s3") {
        throw new java.lang.IllegalArgumentException("--output must be an S3 URI")
      }
      val bucket = outputUri.getHost
      val path = outputUri.getPath.stripPrefix("/")

      val conf = new SparkConf().
        setIfMissing("spark.master", "local[*]").
        setAppName("Building Footprint Elevation").
        set("spark.serializer", classOf[KryoSerializer].getName).
        set("spark.kryo.registrator", classOf[KryoRegistrator].getName).
        set("spark.executionEnv.AWS_PROFILE", Properties.envOrElse("AWS_PROFILE", "default"))

      implicit val ss: SparkSession = SparkSession.builder
        .config(conf)
        .enableHiveSupport
        .getOrCreate
      implicit val sc: SparkContext = ss.sparkContext

      val app = if (buildingsAll) {
        new BuildingsApp(Building.geoJsonURLs)
      } else {
        new BuildingsApp(List(buildingsUri))
      }

      GenerateVT.save(app.tiles, zoom= 15, bucket, path)
    }
  }
)
