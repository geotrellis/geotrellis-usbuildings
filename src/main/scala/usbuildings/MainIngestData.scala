package usbuildings

import java.net.URI

import cats.implicits._
import com.monovore.decline.{CommandApp, Opts}
import geotrellis.proj4.WebMercator
import geotrellis.raster.MultibandTile
import geotrellis.raster.resample.Bilinear
import geotrellis.spark.io.index.ZCurveKeyIndexMethod
import geotrellis.spark.io.kryo.KryoRegistrator
import geotrellis.spark.io.s3.{S3AttributeStore, S3GeoTiffRDD, S3LayerManager, S3LayerWriter}
import geotrellis.spark.pyramid.Pyramid
import geotrellis.spark.tiling.{FloatingLayoutScheme, ZoomedLayoutScheme}
import geotrellis.spark.{LayerId, MultibandTileLayerRDD, SpatialKey, TileLayerMetadata}
import geotrellis.vector.ProjectedExtent
import org.apache.spark.rdd.RDD
import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.sql.SparkSession
import org.apache.spark.{SparkConf, SparkContext}
//import org.apache.spark._
//import geotrellis.spark.tiling._
import geotrellis.raster._
import geotrellis.raster.io._
import geotrellis.spark.io._
import geotrellis.spark.{Metadata, _}
import spray.json.DefaultJsonProtocol._

import scala.util.Properties

// This is an example showing the checker boxes problem with 10m NEDs.
object MainIngestData extends CommandApp(
  name = "nedingest",
  header = "",
  main = {
    //val nedOpt = Opts.option[String]("neds", help = "S3 URI prefix of input ned data").withDefault("s3://dewberry-demo/rasters/10m_full_nation")
    //val nedOpt = Opts.option[String]("neds", help = "S3 URI prefix of input ned data").withDefault("s3://dewberry-demo/rasters/10m/AL")
    val nedOpt = Opts.option[String]("neds", help = "S3 URI prefix of input ned data").withDefault("s3://dewberry-demo/rasters/10m_region4")
    val outputOpt = Opts.option[String]("output", help = "S3 URI prefix of output tiles").withDefault("s3://dewberry-demo/testingests")

    ( nedOpt, outputOpt).mapN { (nedUri, outputUriString) =>
      val outputUri = new URI(outputUriString)
      if (outputUri.getScheme != "s3") {
        throw new java.lang.IllegalArgumentException("--output must be an S3 URI")
      }
      val bucket = outputUri.getHost
      val path = outputUri.getPath.stripPrefix("/")

      //to solve timeout problem
      System.setProperty("sun.net.client.defaultReadTimeout", "60000")

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

      val inputUri = new URI(nedUri)
      if (inputUri.getScheme != "s3") {
        throw new java.lang.IllegalArgumentException("--input must be an S3 URI")
      }
      val bucketInp = inputUri.getHost
      val pathInp = inputUri.getPath.stripPrefix("/")

      val myArr = pathInp.split('/')
      val stateName = myArr(myArr.length - 1)
      run(sc)

      def run(implicit sc: SparkContext) = {
        val layoutScheme = ZoomedLayoutScheme(WebMercator, tileSize = 256)
        var zoom: Int = 6 //dummy low value
        val inputRDD: RDD[(ProjectedExtent, MultibandTile)] = S3GeoTiffRDD.spatialMultiband(bucketInp, pathInp) //pluvial test
        val reprojected: RDD[(SpatialKey, MultibandTile)] with Metadata[TileLayerMetadata[SpatialKey]] = {
          val (_, metadata) = TileLayerMetadata.fromRDD(inputRDD, FloatingLayoutScheme(512))
          val inputTiledRDD =
            inputRDD
              .tileToLayout(metadata.cellType, metadata.layout, Bilinear)
              //.repartition(640) // this is preassigned in build.sbt as 1280 for a m5.2xlarge 20 node cluster.

          val (z, reprojected1) =
            MultibandTileLayerRDD(inputTiledRDD, metadata)
              .reproject(WebMercator, layoutScheme, Bilinear)

          zoom = Math.max(zoom,z)

          reprojected1
        }

        //using s3 for writing the outputs
        val attributeStore = S3AttributeStore(bucket, path)

        // Create the writer that we will use to store the tiles in the local catalog.
        val writer = S3LayerWriter(attributeStore)

        // Pyramiding up the zoom levels, write our tiles out to the local file system.
        Pyramid.upLevels(reprojected, layoutScheme, zoom, Bilinear) { (rdd, z) =>
          val layerId = LayerId("dem_10m_" + stateName, z)
          //val layerId = LayerId("dem_10m_full_nation_", z)
          if (z == 0) {
            val histogram = reprojected.histogram
            attributeStore.write(layerId, "histogram", histogram)
          }
          // If the layer exists already, delete it out before writing
          if (attributeStore.layerExists(layerId)) {
            new S3LayerManager(attributeStore).delete(layerId)
          }
          writer.write(layerId, rdd, ZCurveKeyIndexMethod)
        }
      }
    }
  }
)
