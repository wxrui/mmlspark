// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import com.microsoft.ml.lightgbm._
import com.microsoft.ml.spark.LightGBMUtils.{getBoosterPtrFromModelString, intToPtr, newDoubleArray, newIntArray}
import org.apache.spark.ml.linalg.{DenseVector, SparseVector, Vector}
import org.apache.spark.sql.{SaveMode, SparkSession}

/** Represents a LightGBM Booster learner
  * @param model The string serialized representation of the learner
  */
@SerialVersionUID(777L)
class LightGBMBooster(val model: String) extends Serializable {
  /** Transient variable containing local machine's pointer to native booster
    */
  @transient
  var boosterPtr: SWIGTYPE_p_void = null

  def score(features: Vector, raw: Boolean, classification: Boolean): Array[Double] = {
    // Reload booster on each node
    if (boosterPtr == null) {
      LightGBMUtils.initializeNativeLibrary()
      boosterPtr = getBoosterPtrFromModelString(model)
    }
    val kind =
      if (raw) lightgbmlibConstants.C_API_PREDICT_RAW_SCORE
      else lightgbmlibConstants.C_API_PREDICT_NORMAL
    features match {
      case dense: DenseVector => predictForMat(dense.toArray, kind, classification)
      case sparse: SparseVector => predictForCSR(sparse, kind, classification)
    }
  }

  lazy val numClasses: Int = getNumClasses()

  protected def predictForCSR(sparseVector: SparseVector, kind: Int, classification: Boolean): Array[Double] = {
    var values: Option[(SWIGTYPE_p_void, SWIGTYPE_p_double)] = None
    var indexes: Option[(SWIGTYPE_p_int32_t, SWIGTYPE_p_int)] = None
    var indptrNative: Option[(SWIGTYPE_p_int32_t, SWIGTYPE_p_int)] = None
    var scoredDataOutPtr: Option[SWIGTYPE_p_double] = None
    try {
      val numRows = 1
      val valuesArray = sparseVector.values
      values = Some(newDoubleArray(valuesArray))
      val indexesArray = sparseVector.indices
      indexes = Some(newIntArray(indexesArray))
      val indptr = new Array[Int](numRows + 1)
      indptr(1) = sparseVector.numNonzeros
      indptrNative = Some(newIntArray(indptr))
      val numCols = sparseVector.size

      val datasetParams = "max_bin=255 is_pre_partition=True"
      val dataInt32bitType = lightgbmlibConstants.C_API_DTYPE_INT32
      val data64bitType = lightgbmlibConstants.C_API_DTYPE_FLOAT64
      scoredDataOutPtr = Some(lightgbmlib.new_doubleArray(numRows * numClasses))

      val scoredDataLengthLongPtr = lightgbmlib.new_longp()
      lightgbmlib.longp_assign(scoredDataLengthLongPtr, numRows)
      val scoredDataLength_int64_tPtr = lightgbmlib.long_to_int64_t_ptr(scoredDataLengthLongPtr)

      LightGBMUtils.validate(CSRUtils.LGBM_BoosterPredictForCSR(boosterPtr, indptrNative.get._1, dataInt32bitType,
        indexes.get._1, values.get._1, data64bitType, intToPtr(indptr.length), intToPtr(numRows + 1), intToPtr(numCols),
        kind, -1, datasetParams,
        scoredDataLength_int64_tPtr, scoredDataOutPtr.get), "Booster Predict")
      predToArray(classification, scoredDataOutPtr.get, kind)
    } finally {
      // Delete the input row
      if (values.isDefined) lightgbmlib.delete_doubleArray(values.get._2)
      if (indexes.isDefined) lightgbmlib.delete_intArray(indexes.get._2)
      if (indptrNative.isDefined) lightgbmlib.delete_intArray(indptrNative.get._2)
      // Delete the output scored row
      if (scoredDataOutPtr.isDefined) lightgbmlib.delete_doubleArray(scoredDataOutPtr.get)
    }
  }

  protected def predictForMat(row: Array[Double], kind: Int, classification: Boolean): Array[Double] = {
    var data: Option[(SWIGTYPE_p_void, SWIGTYPE_p_double)] = None
    var scoredDataOutPtr: Option[SWIGTYPE_p_double] = None
    try {
      val numRows = 1
      data = Some(LightGBMUtils.generateData(numRows, Array(row)))
      val data64bitType = lightgbmlibConstants.C_API_DTYPE_FLOAT64

      val numRowsIntPtr = lightgbmlib.new_intp()
      lightgbmlib.intp_assign(numRowsIntPtr, numRows)
      val numRows_int32_tPtr = lightgbmlib.int_to_int32_t_ptr(numRowsIntPtr)
      val scoredDataLengthLongPtr = lightgbmlib.new_longp()
      lightgbmlib.longp_assign(scoredDataLengthLongPtr, numRows)
      val scoredDataLength_int64_tPtr = lightgbmlib.long_to_int64_t_ptr(scoredDataLengthLongPtr)

      val numCols = row.length
      val isRowMajor = 1
      val numColsIntPtr = lightgbmlib.new_intp()
      lightgbmlib.intp_assign(numColsIntPtr, numCols)
      val numCols_int32_tPtr = lightgbmlib.int_to_int32_t_ptr(numColsIntPtr)
      scoredDataOutPtr = Some(lightgbmlib.new_doubleArray(numRows * numClasses))
      val datasetParams = "max_bin=255"
      LightGBMUtils.validate(
        lightgbmlib.LGBM_BoosterPredictForMat(
          boosterPtr, data.get._1, data64bitType, numRows_int32_tPtr,
          numCols_int32_tPtr, isRowMajor, kind,
          -1, datasetParams, scoredDataLength_int64_tPtr, scoredDataOutPtr.get),
        "Booster Predict")
      predToArray(classification, scoredDataOutPtr.get, kind)
    } finally {
      // Delete the input row
      if (data.isDefined) lightgbmlib.delete_doubleArray(data.get._2)
      // Delete the output scored row
      if (scoredDataOutPtr.isDefined) lightgbmlib.delete_doubleArray(scoredDataOutPtr.get)
    }
  }

  def saveNativeModel(session: SparkSession, filename: String, overwrite: Boolean): Unit = {
    if (filename == null || filename.isEmpty()) {
      throw new IllegalArgumentException("filename should not be empty or null.")
    }
    val rdd = session.sparkContext.parallelize(Seq(model))
    import session.sqlContext.implicits._
    val dataset = session.sqlContext.createDataset(rdd)
    val mode = if (overwrite) SaveMode.Overwrite else SaveMode.ErrorIfExists
    dataset.coalesce(1).write.mode(mode).text(filename)
  }

  /**
    * Calls into LightGBM to retrieve the feature importances.
    * @param importanceType Can be "split" or "gain"
    * @return The feature importance values as an array.
    */
  def getFeatureImportances(importanceType: String): Array[Double] = {
    val importanceTypeNum = if (importanceType.toLowerCase.trim == "gain") 1 else 0
    if (boosterPtr == null) {
      LightGBMUtils.initializeNativeLibrary()
      boosterPtr = getBoosterPtrFromModelString(model)
    }
    val numFeaturesOut = lightgbmlib.new_intp()
    LightGBMUtils.validate(
      lightgbmlib.LGBM_BoosterGetNumFeature(boosterPtr, numFeaturesOut),
      "Booster NumFeature")
    val numFeatures = lightgbmlib.intp_value(numFeaturesOut)
    val featureImportances = lightgbmlib.new_doubleArray(numFeatures)
    LightGBMUtils.validate(
      lightgbmlib.LGBM_BoosterFeatureImportance(boosterPtr, -1, importanceTypeNum, featureImportances),
      "Booster FeatureImportance")
    (0 until numFeatures).map(lightgbmlib.doubleArray_getitem(featureImportances, _)).toArray
  }

  /**
    * Retrieve the number of classes from LightGBM Booster
    * @return The number of classes.
    */
  def getNumClasses(): Int = {
    if (boosterPtr == null) {
      LightGBMUtils.initializeNativeLibrary()
      boosterPtr = getBoosterPtrFromModelString(model)
    }
    val numClassesOut = lightgbmlib.new_intp()
    LightGBMUtils.validate(
      lightgbmlib.LGBM_BoosterGetNumClasses(boosterPtr, numClassesOut),
      "Booster NumClasses")
    lightgbmlib.intp_value(numClassesOut)
  }

  private def predToArray(classification: Boolean, scoredDataOutPtr: SWIGTYPE_p_double, kind: Int): Array[Double] = {
    if (classification && numClasses == 1) {
      // Binary classification scenario - LightGBM only returns the value for the positive class
      val pred = lightgbmlib.doubleArray_getitem(scoredDataOutPtr, 0)
      if (kind == lightgbmlibConstants.C_API_PREDICT_RAW_SCORE) {
        // Return the raw score for binary classification
        Array(-pred, pred)
      } else {
        // Return the probability for binary classification
        Array(1 - pred, pred)
      }
    } else {
      (0 until numClasses).map(classNum =>
        lightgbmlib.doubleArray_getitem(scoredDataOutPtr, classNum)).toArray
    }
  }
}
