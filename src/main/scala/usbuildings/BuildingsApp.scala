package usbuildings

import java.net.URL

import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import geotrellis.proj4.{LatLng, WebMercator}
import geotrellis.raster.summary.polygonal.{DoubleHistogramSummary, MaxSummary}
import geotrellis.spark.SpatialKey
import geotrellis.spark.tiling.ZoomedLayoutScheme
import geotrellis.vectortile.VectorTile
import org.apache.spark.rdd.RDD
import org.apache.spark.{HashPartitioner, SparkContext}

import scala.util.Try

class BuildingsApp(
  val buildingsUri: Seq[String]
)(@transient implicit val sc: SparkContext) extends LazyLogging with Serializable {

  /** Explode each layer so we can parallelize reading over layers */

  val allBuildings: RDD[Building] =
    sc.parallelize(buildingsUri, buildingsUri.length)
      .flatMap { url =>
        Building.readFromGeoJson(new URL(url))
      }

  val partitioner = new HashPartitioner(partitions=allBuildings.getNumPartitions * 16) //ravi commenting off partitions to let spark determine optimal

  // Split building geometries over skadi grid, each partition will intersect with N tiles
  val keyedBuildings: RDD[(SpatialKey, Building)] =
    allBuildings.flatMap { building =>
      val keys: Set[SpatialKey] = Terrain.terrainTilesSkadiGrid.mapTransform.keysForGeometry(building.footprint)
      // we may end up duplicating building footprint in memory if it intersects grid boundaries
      keys.map( key => (key, building) )
    }.partitionBy(partitioner)

  // per partition: set of tiles is << set of geometries
  // TODO why can't I just do polygonal summary over RasterSource ?
  def taggedBuildings: RDD[((String, Int), Building)] =
    keyedBuildings.mapPartitions { buildingsPartition =>
      org.gdal.gdal.gdal.SetConfigOption("CPL_VSIL_GZIP_WRITE_PROPERTIES", "NO")

      val histPerTile =
        for {
          (tileKey, buildings) <- buildingsPartition.toArray.groupBy(_._1)
          //rasterSource = Either.catchNonFatal(Terrain.getRasterSource(tileKey)) //original terrain call
          rasterSource = Either.catchNonFatal(Pluvial_2yr.getRasterSource(tileKey))
          (_, building) <- buildings
          maybeRaster = rasterSource.flatMap(rs => Either.catchNonFatal(rs.read(building.footprint.envelope))) //this is original. Works with wgs84 data source.
          //maybeRaster = rasterSource.flatMap(rs => Either.catchNonFatal(rs.read(building.footprint.envelope.reproject(LatLng,WebMercator))))
        } yield {
          maybeRaster match {
            case Left(e: Throwable) => (building.id, building.withError(e.toString))
            case Right(None) => (building.id, building.withError("Envelope not in RasterSource extent"))
            case Right(Some(raster)) => {
              val hist = raster.tile.band(bandIndex = 0)
                .polygonalSummary(
                  extent = raster.extent,
                  polygon = building.footprint,
                  handler = CustomDoubleHistogramSummary)

              (building.id, building.withHistogram(Some(hist)))
            }
          }
        }

      histPerTile.groupBy(_._1).mapValues(_.values.reduce(_ mergeHistograms  _)).toIterator
    }.reduceByKey { (b1, b2) => b1.mergeHistograms(b2) } // Reduce results per building, now with network shuffle


  // TODO: assign buildings to keys at Zoom=X, group buildings by key
  def layoutScheme = ZoomedLayoutScheme(WebMercator)
  def layout = layoutScheme.levelForZoom(15).layout

  // I'm reproject them so I can make WebMercator vector tiles
  def buildingsPerWmTile: RDD[(SpatialKey, Iterable[Building])] =
    taggedBuildings.flatMap { case (id, building) =>
      val wmFootprint = building.footprint.reproject(LatLng, WebMercator)
      val reprojected = building.withFootprint(wmFootprint)
      val layoutKeys: Set[SpatialKey] = layout.mapTransform.keysForGeometry(wmFootprint)
      layoutKeys.toIterator.map( key => (key, reprojected))
    }.groupByKey(partitioner)

  def tiles: RDD[(SpatialKey, VectorTile)] =
    buildingsPerWmTile.map { case (key, buildings) =>
      val extent = layout.mapTransform.keyToExtent(key)
      (key, Util.makeVectorTile(extent, buildings))
    }
}
