// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import java.io.File
import java.net.{URI, URL}

import com.microsoft.ml.spark.FileUtilities.File
import com.microsoft.ml.spark.IOImplicits._
import org.apache.commons.io.FileUtils
import org.apache.spark.ml.linalg.DenseVector
import org.apache.spark.ml.util.MLReadable
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.{col, udf}
import org.apache.spark.sql.types.StringType

trait NetworkUtils extends CNTKTestUtils with FileReaderUtils {

  lazy val modelDir = new File(filesRoot, "CNTKModel")
  lazy val modelDownloader = new ModelDownloader(session, modelDir.toURI)

  lazy val resNetUri: URI = new File(modelDir, "ResNet50_ImageNet.model").toURI
  lazy val resNet: ModelSchema = modelDownloader.downloadByName("ResNet50")

  lazy val images: DataFrame = session.read.image.load(imagePath)
    .withColumnRenamed("image", inputCol)
  lazy val binaryImages: DataFrame = session.read.binary.load(imagePath)
    .select(col("value.bytes").alias(inputCol))

  lazy val groceriesPath = s"${sys.env("DATASETS_HOME")}/Images/Grocery/"
  lazy val groceryImages: DataFrame = session.read.image
    .option("dropInvalid", true)
    .load(groceriesPath + "**")
    .withColumnRenamed("image", inputCol)

  lazy val greyscaleImageLocation: String = {
    val loc = "/tmp/greyscale.jpg"
    val f = new File(loc)
    if (f.exists()) {f.delete()}
    FileUtils.copyURLToFile(new URL("https://mmlspark.blob.core.windows.net/datasets/LIME/greyscale.jpg"), f)
    loc
  }

  lazy val greyscaleImage: DataFrame = session
    .read.image.load(greyscaleImageLocation)
    .select(col("image").alias(inputCol))

  lazy val greyscaleBinary: DataFrame = session
    .read.binary.load(greyscaleImageLocation)
    .select(col("value.bytes").alias(inputCol))

  def resNetModel(): ImageFeaturizer = new ImageFeaturizer()
    .setInputCol(inputCol)
    .setOutputCol(outputCol)
    .setModel(resNet)

}

class ImageFeaturizerSuite extends TransformerFuzzing[ImageFeaturizer]
  with NetworkUtils {

  test("Image featurizer should reproduce the CIFAR10 experiment") {
    print(session)
    val model = new ImageFeaturizer()
      .setInputCol(inputCol)
      .setOutputCol(outputCol)
      .setModelLocation(s"${sys.env("DATASETS_HOME")}/CNTKModel/ConvNet_CIFAR10.model")
      .setCutOutputLayers(0)
      .setLayerNames(Array("z"))
    val result = model.transform(images)
    compareToTestModel(result)
  }

  test("structured streaming") {

    val model = new ImageFeaturizer()
      .setInputCol("image")
      .setOutputCol(outputCol)
      .setModelLocation(s"${sys.env("DATASETS_HOME")}/CNTKModel/ConvNet_CIFAR10.model")
      .setCutOutputLayers(0)
      .setLayerNames(Array("z"))

    val imageDF = session
      .readStream
      .image
      .load(cifarDirectory)

    val resultDF = model.transform(imageDF)

    val q1 = resultDF.writeStream
      .format("memory")
      .queryName("images")
      .start()

    try {
      tryWithRetries() { () =>
        assert(session.sql("select * from images").count() == 6)
      }
    } finally {
      q1.stop()
    }
  }

  test("the Image feature should work with the modelSchema") {
    val result = resNetModel().setCutOutputLayers(0).transform(images)
    compareToTestModel(result)
  }

  test("the Image feature should work with the modelSchema + new images") {
    val newImages = session.read.image
      .load(cifarDirectory)
      .withColumnRenamed("image", "cntk_images")

    val result = resNetModel().setCutOutputLayers(0).transform(newImages)
    compareToTestModel(result)
  }

  test("Image featurizer should work with ResNet50", TestBase.Extended) {
    val result = resNetModel().transform(images)
    val resVec = result.select(outputCol).collect()(0).getAs[DenseVector](0)
    assert(resVec.size == 1000)
  }

  test("Image featurizer should work with ResNet50 in greyscale", TestBase.Extended) {
    val result = resNetModel().transform(greyscaleImage)
    val resVec = result.select(outputCol).collect()(0).getAs[DenseVector](0)
    assert(resVec.size == 1000)
  }

  test("Image featurizer should work with ResNet50 in greyscale binary", TestBase.Extended) {
    val result = resNetModel().transform(greyscaleBinary)
    val resVec = result.select(outputCol).collect()(0).getAs[DenseVector](0)
    assert(resVec.size == 1000)
  }

  test("Image featurizer should work with ResNet50 Binary + nulls", TestBase.Extended) {
    import session.implicits._
    val corruptImage = Seq("fooo".toCharArray.map(_.toByte))
      .toDF(inputCol)
    val df = binaryImages.union(corruptImage)

    val resultDF = resNetModel().transform(df)
    val result = resultDF.select(outputCol).collect()
    assert(result(0).getAs[DenseVector](0).size == 1000)
  }

  test("Image featurizer should work with ResNet50 Binary 2 + nulls", TestBase.Extended) {
    import session.implicits._
    val corruptImage = Seq("fooo".toCharArray.map(_.toByte))
      .toDF(inputCol)
    val df = binaryImages.union(corruptImage)

    val resultDF = resNetModel().transform(df)
    val result = resultDF.select(outputCol).collect()
    assert(result(0).getAs[DenseVector](0).size == 1000)
  }

  test("Image featurizer should correctly classify an image", TestBase.Extended) {
    val testImg: DataFrame = session
      .read.image.load(s"$filesRoot/Images/Grocery/testImages/WIN_20160803_11_28_42_Pro.jpg")
      .withColumnRenamed("image", inputCol)
    val result = resNetModel().transform(testImg)
    val resVec = result.select(outputCol).collect()(0).getAs[DenseVector](0)
    assert(resVec.argmax == 760)
  }

  test("Image featurizer should work with ResNet50 and powerBI", TestBase.Extended) {
    val images = groceryImages.withColumnRenamed(inputCol, "image").coalesce(1)
    println(images.count())

    val result = resNetModel().setInputCol("image").transform(images)
      .withColumn("foo", udf({ x: DenseVector => x(0).toString }, StringType)(col("out")))
      .select("foo")

    PowerBIWriter.write(result, sys.env("MML_POWERBI_URL"), Map("concurrency" -> "1"))
  }

  test("test layers of network", TestBase.Extended) {
    (0 to 9).foreach({ i =>
      val model = new ImageFeaturizer()
        .setModel(resNet)
        .setInputCol(inputCol)
        .setOutputCol(outputCol)
        .setCutOutputLayers(i)
      val result = model.transform(images)
    })
  }

  val reader: MLReadable[_] = ImageFeaturizer

  override def testObjects(): Seq[TestObject[ImageFeaturizer]] = Seq(
    new TestObject(resNetModel(), images)
  )
}
