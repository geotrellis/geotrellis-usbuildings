package usbuildings


import java.net.URI

import geotrellis.contrib.vlm._
import geotrellis.contrib.vlm.geotiff._
import geotrellis.vector._
import geotrellis.proj4._
import geotrellis.raster._
import geotrellis.raster.reproject.Reproject
import geotrellis.raster.resample.ResampleMethod
import geotrellis.raster.io.geotiff.{GeoTiffMultibandTile, MultibandGeoTiff, OverviewStrategy}
import geotrellis.raster.io.geotiff.reader.GeoTiffReader
import geotrellis.spark.{LayerId, Metadata, SpatialKey, TileLayerMetadata}
import geotrellis.spark.io._
import geotrellis.contrib.vlm.avro._
import geotrellis.contrib.vlm.gdal.GDALRasterSource
import geotrellis.raster.{MultibandTile, Tile}
import usbuildings.Terrain.tileUri

object Pluvial_2yr {

 //Only trying this for DC area.
  val catalogPath = "s3://dewberry-demo/geotl_prod"
  val lyrId:LayerId = LayerId("atkinspluvial_2yr_dc_wgs84",15) //only works with this because buildings are in wgs84
  //val lyrId:LayerId = LayerId("atkinspluvial_2yr_dc",15) //tried reprojecting the building footprint and used this. I think this did not work.

  //val lyrId:LayerId = LayerId("atkinspluvial_fema_r_iv_2yr_sc",15) //tried this geography and it took a long time (~9+ hours)


  //Observe that the spatial key is not used when returning Geotrellis Raster Source.
  def getRasterSource(tileKey: SpatialKey): RasterSource = {
    GeotrellisRasterSource(catalogPath,lyrId)
  }

}
