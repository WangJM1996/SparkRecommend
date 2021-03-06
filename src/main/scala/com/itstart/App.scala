package com.atguigu.dataloader
import java.net.InetAddress

import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.{MongoClient, MongoClientURI}
import org.apache.spark.SparkConf
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.transport.client.PreBuiltTransportClient
/**
  * Movie数据集，数据集字段通过^分割
  *
  * 1^                                              电影的id
  * Toy Story (1995)^                               电影的名称
  * ^81 minutes^                                    电影时长
  * March 20, 2001^                                 电影发行时间
  * 1995^                                           电影的拍摄日期
  * English ^                                       电影的语言
  * Adventure|Animation|Children|Comedy|Fantasy ^    电影的类型
  * Tom Hanks|Tim Allen|Don Rickles|Jim Varney|^      电影的演员
  * John Lasseter                                   电影的导演
  *
  *
  * Rating数据集，用户对于电影的评分，用，分割
  * 1，                  用户的id
  * 31，                 电影的id
  * 2.5，                 用户对电影的评分
  * 1260759144            用户对电影的评分时间
  *
  *
  * Tag数据集，用户对于电影的标签数据集，用，分割
  * 15，               用户的id
  * 1955，             电影的id
  * dentist，          标签的内容
  * 1193435061          用户对于电影打标签的时间
  *
  */
object DataLoader {

  // Moive在MongoDB中的Collection名称【表】
  val MOVIES_COLLECTION_NAME = "Movie"

  // Rating在MongoDB中的Collection名称【表】
  val RATINGS_COLLECTION_NAME = "Rating"

  // Tag在MongoDB中的Collection名称【表】
  val TAGS_COLLECTION_NAME = "Tag"

  // Movie在ElasticSearch中的Index名称
  val ES_MOVIE_TYPE_NAME = "Movie"

  // Tag在ElasticSearch中的Index名称
  val ES_TAG_TYPE_NAME = "Tag"

  // 配置主机名:端口号的正则表达式
  val ES_HOST_PORT_REGEX = "(.+):(\\d+)".r

  /**
    * Store Data In ElasticSearch
    *
    * @param movies 电影数据集
    * @param esConf ElasticSearch的配置对象
    */
  private def storeMoiveDataInES(movies: DataFrame)(implicit esConf: ESConfig): Unit = {

    // 需要操作的Index名称
    val indexName = esConf.index

    // 新建一个到ES的连接配置
    var settings: Settings = Settings.builder().put("cluster.name", esConf.clusterName).build()

    // 创建到ES的连接客户端
    val esClient = new PreBuiltTransportClient(settings)

    //对于设定的多个Node分别通过正则表达式进行模式匹配，并添加到客户端实例
    esConf.transportHosts.split(";")
      .foreach {
        case ES_HOST_PORT_REGEX(host: String, port: String) =>
          esClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName(host), port.toInt))
      }

    // 检查如果Index存在，那么删除Index
    if (esClient.admin().indices().exists(new IndicesExistsRequest(indexName)).actionGet().isExists) {
      // 删除Index
      esClient.admin().indices().delete(new DeleteIndexRequest(indexName)).actionGet()
    }
    // 创建Index
    esClient.admin().indices().create(new CreateIndexRequest(indexName)).actionGet()

    // 声明写出时的ES配置信息
    val movieOptions = Map("es.nodes" -> esConf.httpHosts,
      "es.http.timeout" -> "100m",
      "es.mapping.id" -> "mid")

    // 电影数据写出时的Type名称【表】
    val movieTypeName = s"$indexName/$ES_MOVIE_TYPE_NAME"

    // 标签数据写出时的Type名称【表】
    //val tagTypeName = s"$indexName/$ES_TAG_TYPE_NAME"

    // 将Movie信息保存到ES
    movies
      .write.options(movieOptions)
      .mode("overwrite")
      .format("org.elasticsearch.spark.sql")
      .save(movieTypeName)

  }

  /**
    * Store Data In MongoDB
    *
    * @param movies    电影数据集
    * @param ratings   评分数据集
    * @param tags      标签数据集
    * @param mongoConf MongoDB的配置
    */
  private def storeDataInMongo(movies: DataFrame, ratings: DataFrame, tags: DataFrame)(implicit mongoConf: MongoConfig): Unit = {

    // 创建到MongoDB的连接
    val mongoClient = MongoClient(MongoClientURI(mongoConf.uri))

    // 删除Movie的Collection
    mongoClient(mongoConf.db)(MOVIES_COLLECTION_NAME).dropCollection()

    // 删除Rating的Collection
    mongoClient(mongoConf.db)(RATINGS_COLLECTION_NAME).dropCollection()

    // 删除Tag的Collection
    mongoClient(mongoConf.db)(TAGS_COLLECTION_NAME).dropCollection()

    //将Movie数据集写入到MongoDB
    movies
      .write
      .option("uri", mongoConf.uri)
      .option("collection", MOVIES_COLLECTION_NAME)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()

    //将Rating数据集写入到MongoDB
    ratings
      .write.option("uri", mongoConf.uri)
      .option("collection", RATINGS_COLLECTION_NAME)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()

    //将Tag数据集写入到MongoDB
    tags
      .write.option("uri", mongoConf.uri)
      .option("collection", TAGS_COLLECTION_NAME)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()

    //创建索引
    mongoClient(mongoConf.db)(MOVIES_COLLECTION_NAME).createIndex(MongoDBObject("mid" -> 1))
    mongoClient(mongoConf.db)(RATINGS_COLLECTION_NAME).createIndex(MongoDBObject("mid" -> 1))
    mongoClient(mongoConf.db)(RATINGS_COLLECTION_NAME).createIndex(MongoDBObject("uid" -> 1))
    mongoClient(mongoConf.db)(TAGS_COLLECTION_NAME).createIndex(MongoDBObject("mid" -> 1))
    mongoClient(mongoConf.db)(TAGS_COLLECTION_NAME).createIndex(MongoDBObject("uid" -> 1))

    //关闭MongoDB的连接
    mongoClient.close()
  }

  def main(args: Array[String]): Unit = {

    // [mid,name,descri,timelong,issue,shoot,language,genres,actors,directors]
    val DATAFILE_MOVIES = "D:\\IdeaProjects\\RecommendSystem\\recommender\\dataloader\\src\\main\\resources\\small\\movies.csv"

    // [userId,movieId,rating,timestamp]
    val DATAFILE_RATINGS = "D:\\IdeaProjects\\RecommendSystem\\recommender\\dataloader\\src\\main\\resources\\small\\ratings.csv"

    // [userId,movieId,tag,timestamp]
    val DATAFILE_TAGS = "D:\\IdeaProjects\\RecommendSystem\\recommender\\dataloader\\src\\main\\resources\\small\\tags.csv"

    //创建全局配置
    val params = scala.collection.mutable.Map[String, Any]()
    params += "spark.cores" -> "local[*]"
    params += "mongo.uri" -> "mongodb://BigData:27017/recommender"
    params += "mongo.db" -> "recommender"
    params += "es.httpHosts" -> "BigData:9200"
    params += "es.transportHosts" -> "BigData:9300"
    params += "es.index" -> "recommender"
    params += "es.cluster.name" -> "es-cluster"

    // 声明Spark的配置信息
    val conf = new SparkConf().setAppName("Dataloader").setMaster(params("spark.cores").asInstanceOf[String])

    // 创建SparkSession
    val spark = SparkSession.builder()
      .config(conf)
      .getOrCreate()

    // 引入SparkSession内部的隐式转换
    import spark.implicits._

    // 定义MongoDB的配置对象
    implicit val mongoConf = new MongoConfig(params("mongo.uri").asInstanceOf[String], params("mongo.db").asInstanceOf[String])

    // 定义ElasticSearch的配置对象
    implicit val esConf = new ESConfig(params("es.httpHosts").asInstanceOf[String], params("es.transportHosts").asInstanceOf[String], params("es.index").asInstanceOf[String], params("es.cluster.name").asInstanceOf[String])

    // 加载Movie数据集
    val movieRDD = spark.sparkContext.textFile(DATAFILE_MOVIES)

    // 加载Rating数据集
    val ratingRDD = spark.sparkContext.textFile(DATAFILE_RATINGS)

    // 加载Tag数据集
    val tagRDD = spark.sparkContext.textFile(DATAFILE_TAGS)

    // 将电影RDD转换为DataFrame
    val movieDF = movieRDD.map(line => {
      val x = line.split("\\^")
      //Movie(x(0).trim.toInt, x(1).trim, x(2).trim, x(3).trim, x(4).trim, x(5).trim, x(6).trim.split("\\|"), x(7).trim.split("\\|"), x(8).trim.split("\\|"), x(9).trim.split("\\|"))
      Movie(x(0).trim.toInt, x(1).trim, x(2).trim, x(3).trim, x(4).trim, x(5).trim, x(6).trim, x(7).trim, x(8).trim, x(9).trim)
    }).toDF()

    // 将评分RDD转换为DataFrame
    val ratingDF = ratingRDD.map(line => {
      val x = line.split(",")
      Rating(x(0).toInt, x(1).toInt, x(2).toDouble, x(3).toInt)
    }).toDF()

    // 将标签RDD转换为DataFrame
    val tagDF = tagRDD.map(line => {
      val x = line.split(",")
      Tag(x(0).toInt, x(1).toInt, x(2).toString, x(3).toInt)
    }).toDF()

    //缓存
    movieDF.cache()
    tagDF.cache()

    //引入内置函数库
    import org.apache.spark.sql.functions._

    //将tagDF中的标签合并在一起
    val tagCollectDF = tagDF.groupBy($"mid").agg(concat_ws("|", collect_set($"tag")).as("tags"))

    //将tags合并到movie数据集中产生新的movie数据集
    val esMovieDF = movieDF.join(tagCollectDF,Seq("mid","mid"),"left").select("mid","name","descri","timelong","issue","shoot","language","genres","actors","directors","tags")

    // 将数据保存到ES
    storeMoiveDataInES(esMovieDF);

    // 将数据保存到MongoDB
    storeDataInMongo(movieDF, ratingDF, tagDF)

    //去除缓存
    tagDF.unpersist()
    movieDF.unpersist()

    //关闭Spark
    spark.close()

  }

}
