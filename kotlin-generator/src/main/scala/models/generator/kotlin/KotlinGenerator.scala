package models.generator.kotlin

import java.io.StringWriter

import com.fasterxml.jackson.annotation._
import com.fasterxml.jackson.core.{JsonGenerator, JsonParser, Version}
import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.module.SimpleModule
import com.jakewharton.retrofit2.adapter.rxjava2.HttpException
import com.squareup.kotlinpoet._
import io.apibuilder.generator.v0.models.{File, InvocationForm}
import io.apibuilder.spec.v0.models._
import io.reactivex.Single
import lib.Datatype
import lib.generator.CodeGenerator
import org.threeten.bp.{Instant, LocalDate}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

class KotlinGenerator
  extends CodeGenerator
    with KotlinUtil {

  private implicit def classToClassName(clazz: java.lang.Class[_]): ClassName = new ClassName(clazz.getPackage.getName, clazz.getSimpleName)

  private val kdocClassMessage = s"This code was generated by [${classOf[KotlinGenerator].getName}]\n"

  override def invoke(form: InvocationForm): Either[Seq[String], Seq[File]] = {
    Right(new GeneratorHelper(form.service).generateSourceFiles(form.service))
  }

  class GeneratorHelper(service: Service) {

    private val nameSpace = makeNameSpace(service.namespace)
    private val modelsNameSpace = nameSpace + ".models"
    private val modelsDirectoryPath = createDirectoryPath(modelsNameSpace)

    private val sharedJacksonSpace = modelsNameSpace
    private val sharedObjectMapperClassName = "JacksonObjectMapperFactory"

    //Errors
    private val errorsHelperClassName = "ErrorsHelper"

    val commonNetworkErrorsClassName = new ClassName(modelsNameSpace, "CommonNetworkErrors")
    val eitherErrorTypeClassName = new ClassName(modelsNameSpace, "EitherCallOrCommonNetworkError")
    val callErrorEitherErrorTypeClassName = new ClassName(eitherErrorTypeClassName.getCanonicalName, "CallError")
    val commonErrorEitherErrorTypeClassName = new ClassName(eitherErrorTypeClassName.getCanonicalName, "CommonNetworkError")

    val apiNetworkCallResponseTypeClassName = new ClassName(modelsNameSpace, "ApiNetworkCallResponse")
    val errorResponsesString = "ErrorResponses"
    private val processCommonNetworkErrorString = "processCommonNetworkError"


    private val commonNetworkHttpErrorsList = Seq(
      (404, "ServerNotFound"),
      (500, "ServerError"),
    )
    private val serverTimeOutErrorClassName = "ServerTimeOut"
    private val serverUnknownErrorClassName = "UnknownNetworkError"


    def createDirectoryPath(namespace: String) = namespace.replace('.', '/')

    def generateEnum(enum: io.apibuilder.spec.v0.models.Enum): File = {
      val className = toClassName(enum.name)

      val builder = TypeSpec.enumBuilder(className)
        .addModifiers(KModifier.PUBLIC)
        .addKdoc(kdocClassMessage)

      enum.description.map(builder.addKdoc(_))

      val allEnumValues = enum.values ++ Seq(io.apibuilder.spec.v0.models.EnumValue(undefinedEnumName, Some(undefinedEnumName)))

      allEnumValues.foreach(value => {
        val annotation = AnnotationSpec.builder(classOf[JsonProperty]).addMember("\"" + value.name + "\"")
        builder.addEnumConstant(toEnumName(value.name), TypeSpec.anonymousClassBuilder("\"" + value.name + "\"").addAnnotation(annotation.build()).build())
      })

      val nameField = "jsonProperty"
      val nameFieldType = ClassName.bestGuess("kotlin.String")
      builder.addProperty(PropertySpec.builder(nameField, nameFieldType, KModifier.PRIVATE, KModifier.FINAL).build())

      val constructorWithParams = FunSpec.constructorBuilder()
      val constructorParameter = ParameterSpec.builder(nameField, nameFieldType)
      constructorWithParams.addParameter(constructorParameter.build)
      constructorWithParams.addStatement(s"this.$nameField = $nameField")
      builder.primaryConstructor(constructorWithParams.build())

      val toStringMethod = FunSpec.builder("toString").addModifiers(KModifier.OVERRIDE, KModifier.PUBLIC).returns(nameFieldType)
      toStringMethod.addStatement(s"return $nameField")
      builder.addFunction(toStringMethod.build)

      makeFile(className, builder)
    }

    def getRetrofitReturnTypeWrapperClass(): ClassName = classToClassName(classOf[Single[Void]])

    def generateUnionType(union: Union): File = {
      val className = toClassName(union.name)

      val builder = TypeSpec.interfaceBuilder(className)
        .addModifiers(KModifier.PUBLIC)
        .addKdoc(kdocClassMessage)

      val jsonIgnorePropertiesAnnotation = AnnotationSpec.builder(classOf[JsonIgnoreProperties])
        .addMember("ignoreUnknown=true")
      builder.addAnnotation(jsonIgnorePropertiesAnnotation.build)

      val jsonAnnotationBuilder = AnnotationSpec.builder(classOf[JsonTypeInfo])
      jsonAnnotationBuilder.addMember("use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME")
      if (union.discriminator.isDefined) {
        jsonAnnotationBuilder.addMember("include = com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY")
        jsonAnnotationBuilder.addMember("property = \"" + union.discriminator.get + "\"")
      } else {
        jsonAnnotationBuilder.addMember("include", "com.fasterxml.jackson.annotation.JsonTypeInfo.As.WRAPPER_OBJECT")
      }

      builder.addAnnotation(jsonAnnotationBuilder.build)

      val jsonSubTypesAnnotationBuilder = AnnotationSpec.builder(classOf[JsonSubTypes])
      union.types.foreach(u => {
        jsonSubTypesAnnotationBuilder
          .addMember("%L",
            AnnotationSpec.builder(classOf[JsonSubTypes.Type])
            .addMember("value = %L", dataTypeFromField(u.`type`, modelsNameSpace) + "::class")
            .addMember("name = %S", u.`type`)
            .build()
          )
      })

      builder.addAnnotation(jsonSubTypesAnnotationBuilder.build())

      union.description.map(builder.addKdoc(_))
      makeFile(className, builder)
    }

    def generateModel(model: Model, relatedUnions: Seq[Union]): File = {
      val className = toClassName(model.name)

      val builder = TypeSpec.classBuilder(className)
        .addModifiers(KModifier.PUBLIC, KModifier.DATA)
        .addKdoc(kdocClassMessage)

      val jsonIgnorePropertiesAnnotation = AnnotationSpec.builder(classOf[JsonIgnoreProperties]).addMember("ignoreUnknown=true")
      builder.addAnnotation(jsonIgnorePropertiesAnnotation.build)

      builder.addSuperinterface(classOf[java.io.Serializable], emptyCodeBlock)

      model.description.map(builder.addKdoc(_))

      val constructorWithParams = FunSpec.constructorBuilder()

      val unionClassTypeNames = relatedUnions.map { u => new ClassName(modelsNameSpace, toClassName(u.name)) }
      builder.addSuperinterfaces(unionClassTypeNames.asJava)

      relatedUnions.headOption.map { _ =>
        val jsonAnnotationBuilder = AnnotationSpec.builder(classOf[JsonTypeInfo])
        jsonAnnotationBuilder.addMember("use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NONE")
        builder.addAnnotation(jsonAnnotationBuilder.build())
      }

      val propSpecs = new java.util.ArrayList[PropertySpec]
      model.fields.foreach(field => {

        val fieldSnakeCaseName = field.name
        val arrayParameter = isParameterArray(field.`type`)
        val fieldCamelCaseName = toParamName(fieldSnakeCaseName, true)

        val kotlinDataType = dataTypeFromField(field.`type`, modelsNameSpace)

        val annotation = AnnotationSpec.builder(classOf[JsonProperty]).addMember("\"" + field.name + "\"")
        val getterAnnotation = AnnotationSpec.builder(classOf[JsonProperty]).addMember("\"" + field.name + "\"").useSiteTarget(AnnotationSpec.UseSiteTarget.GET)
        val constructorParameter = ParameterSpec.builder(fieldCamelCaseName, if (field.required) kotlinDataType.asNonNullable() else kotlinDataType.asNullable())
        constructorWithParams.addParameter(constructorParameter.build)
        propSpecs.add(
          PropertySpec.builder(fieldCamelCaseName, if (field.required) kotlinDataType.asNonNullable() else kotlinDataType.asNullable())
            .initializer(fieldCamelCaseName)
            .addAnnotation(annotation.build())
            .addAnnotation(getterAnnotation.build())
            .build()
        )
      })

      builder.primaryConstructor(constructorWithParams.build).addProperties(propSpecs)

      val toJsonString = FunSpec.builder("toJsonString").addModifiers(KModifier.PUBLIC).returns(ClassName.bestGuess("kotlin.String")) //.addException(classOf[JsonProcessingException])
      toJsonString.addStatement(s"return ${sharedJacksonSpace}.${sharedObjectMapperClassName}.create().writeValueAsString(this)")
      builder.addFunction(toJsonString.build)

      val companionBuilder = TypeSpec.companionObjectBuilder()
      val fromBuilder = FunSpec.builder("parseJson").addModifiers(KModifier.PUBLIC).addParameter("json", ClassName.bestGuess("kotlin.String"))
      val modelType = ClassName.bestGuess(modelsNameSpace + "." + className)
      fromBuilder.returns(modelType)
      fromBuilder.addStatement(s"return ${sharedJacksonSpace}.${sharedObjectMapperClassName}.create().readValue( json, ${modelType.simpleName() + "::class.java"})")
      companionBuilder.addFunction(fromBuilder.build)
      builder.companionObject(companionBuilder.build())


      makeFile(className, builder)
    }

    def generateResource(resource: Resource): File = {
      val className = toClassName(resource.plural) + "Client"

      val builder = TypeSpec.interfaceBuilder(className)
        .addModifiers(KModifier.PUBLIC)
        .addKdoc(kdocClassMessage)


      resource.operations.foreach { operation =>

        val maybeAnnotationClass = operation.method match {
          case Method.Get => Some(classOf[retrofit2.http.GET])
          case Method.Post => Some(classOf[retrofit2.http.POST])
          case Method.Put => Some(classOf[retrofit2.http.PUT])
          case Method.Patch => Some(classOf[retrofit2.http.PATCH])
          case Method.Delete => Some(classOf[retrofit2.http.DELETE])
          case Method.Head => Some(classOf[retrofit2.http.HEAD])
          case Method.Connect => None
          case Method.Options => None
          case Method.Trace => None
          case _ => None
        }

        import RetrofitUtil._

        val retrofitPath = toRetrofitPath(operation.path)

        maybeAnnotationClass.map(annotationClass => {

          val methodAnnotation = AnnotationSpec.builder(annotationClass).addMember("value=\"" + retrofitPath + "\"").build()
          val methodName =
            if (operation.path == "/")
              toMethodName(operation.method.toString.toLowerCase)
            else
              toMethodName(operation.method.toString.toLowerCase + "_" + operation.path.replaceAll("/", "_"))

          val method = FunSpec.builder(methodName).addModifiers(KModifier.PUBLIC).addModifiers(KModifier.ABSTRACT)

          operation.description.map(description => {
            method.addKdoc(description)
          })

          operation.deprecation.map(deprecation => {
            val deprecationAnnotation = AnnotationSpec.builder(classOf[Deprecated]).build
            method.addAnnotation(deprecationAnnotation)
            deprecation.description.map(description => {
              method.addKdoc("\n@deprecated: " + description)
            })
          })

          method.addAnnotation(methodAnnotation)

          operation.body.map(body => {
            val bodyType = dataTypeFromField(body.`type`, modelsNameSpace)

            val parameter = ParameterSpec.builder(toParamName(body.`type`, true), bodyType)
            val annotation = AnnotationSpec.builder(classOf[retrofit2.http.Body]).build
            parameter.addAnnotation(annotation)
            method.addParameter(parameter.build())
          })

          val parametersCache = ListBuffer[ParameterSpec]()

          operation.parameters.foreach(parameter => {

            val maybeAnnotationClass = parameter.location match {
              case ParameterLocation.Path => Some(classOf[retrofit2.http.Path])
              case ParameterLocation.Query => Some(classOf[retrofit2.http.Query])
              case ParameterLocation.Form => Some(classOf[retrofit2.http.Query])
              case ParameterLocation.Header => Some(classOf[retrofit2.http.Header])
              case _ => None
            }

            maybeAnnotationClass.map(annotationClass => {
              val parameterType: TypeName = dataTypeFromField(parameter.`type`, modelsNameSpace)
              val param = ParameterSpec.builder(toParamName(parameter.name, true), if (parameter.required) parameterType.asNonNullable() else parameterType.asNullable())

              parametersCache += (param.build())

              val annotation = AnnotationSpec.builder(annotationClass).addMember("value=\"" + parameter.name + "\"").build
              param.addAnnotation(annotation)
              method.addParameter(param.build)

            })
          })

          /*
          this is where it gets a little ugly with apidoc/retrofit mapping.
          apidoc says "map the response code to response type", for example:

          "responses": {
            "201": { "type": "checkout_session"},
            "400": {"type": "error_response"},
            "401": {"type": "error_response"},
            "422": {"type": "error_response"}
          }

          retrofit, on the other hand, treats codes 200-299 as success and others as failure

          I think in most cases we can find a single 200-299 result and map it as success, and for other
          codes clients can do special handling based on response codes (without understanding the response object)

         */

          val maybeSuccessfulResponse = operation.responses.find(response => {
            response.code.isInstanceOf[ResponseCodeInt] &&
              response.code.asInstanceOf[ResponseCodeInt].value >= 200 &&
              response.code.asInstanceOf[ResponseCodeInt].value < 299
          })

          maybeSuccessfulResponse.map(successfulResponse => {
            val returnType = dataTypeFromField(successfulResponse.`type`, modelsNameSpace)
            val retrofitWrappedClassname = getRetrofitReturnTypeWrapperClass()
            method.returns(ParameterizedTypeName.get(retrofitWrappedClassname, returnType))
          })

          builder.addFunction(method.build)


          /* ----- Error responses --------
          Since we want to keep Rx's success/error format, and we can't make retrofit return something custom,
          we create for each resource that has non 200 error responses defined
          - a sealed class that has all the error types
          - a lambda that converts retrofit's exception into above sealed class
          - a convenience method getCallAndErrors to return both the rx single, and the lambda.
          the client of this generated code is responsible for calling the lambda to get all the error responses
          */


          val errorResponses = operation.responses.filter(response => {
            response.code.isInstanceOf[ResponseCodeInt] &&
              response.code.asInstanceOf[ResponseCodeInt].value >= 300
          })
          if (errorResponses.nonEmpty) {

            val callObjectName = new ClassName(modelsNameSpace + "." + className, methodName.capitalize + "Call")
            val callObjectBuilder = TypeSpec.objectBuilder(callObjectName)

            val callErrorResponseSealedClassName = new ClassName(callObjectName.getCanonicalName, errorResponsesString)
            val callErrorResopnseSealedClassBuilder = TypeSpec.classBuilder(callErrorResponseSealedClassName)
              .addModifiers(KModifier.SEALED)

            operation.description.map(description => {
              callErrorResopnseSealedClassBuilder.addKdoc("Error Responses for " + methodName + " - " + description)
            })

            val companionObject = TypeSpec.companionObjectBuilder()
            val throwableTypeName = getThrowableClassName().asInstanceOf[TypeName]
            val throwableToErrorTypesLambda = LambdaTypeName.get(null, Array(throwableTypeName), ParameterizedTypeName.get(eitherErrorTypeClassName, callErrorResponseSealedClassName))
            val toErrorCodeBlockBuilder = CodeBlock.builder()
              toErrorCodeBlockBuilder.add("{\n" +
                "t ->\n" +
              "                when (t) {\n" + "" +
              "                    is %T -> {\n" +
              "                        val body: String? = t.response().errorBody()?.string()\n" +
              "                            when (t.code()) {\n"

              , classOf[HttpException])



            val commonUnknownNetworkErrorType = new ClassName(commonNetworkErrorsClassName.getCanonicalName, serverUnknownErrorClassName)

            errorResponses.map(errorResponse => {
              val responseCodeString = errorResponse.code.asInstanceOf[ResponseCodeInt].value.toString
              val errorTypeNameString = "Error" + responseCodeString
              if (errorResponse.`type` == Datatype.Primitive.Unit) {
                callErrorResopnseSealedClassBuilder.addType(TypeSpec.objectBuilder(errorTypeNameString).superclass(callErrorResponseSealedClassName).build())
              } else {
                val errorPayloadType = dataTypeFromField(errorResponse.`type`, modelsNameSpace).asInstanceOf[ClassName]
                val errorPayloadNameString = "data"
                val errorResponseDataClass = TypeSpec.classBuilder(errorTypeNameString)
                  .addModifiers(KModifier.DATA)
                  .primaryConstructor(FunSpec.constructorBuilder().addParameter(errorPayloadNameString, errorPayloadType).build())
                  .addProperty(PropertySpec.builder(errorPayloadNameString, errorPayloadType)
                    .initializer(errorPayloadNameString)
                    .build())
                  .superclass(callErrorResponseSealedClassName)
                  .build()

                toErrorCodeBlockBuilder.add("                            " + responseCodeString + " -> body?.let { %T<" + errorResponsesString + "> (" + errorResponsesString + "." + errorTypeNameString + "(" + errorPayloadType.simpleName() + ".parseJson(body))) } ?: %T(%T) \n",
                  callErrorEitherErrorTypeClassName,
                  commonErrorEitherErrorTypeClassName, commonUnknownNetworkErrorType)

                callErrorResopnseSealedClassBuilder.addType(errorResponseDataClass)
              }
            })
            toErrorCodeBlockBuilder.add("                        else -> %T(%T." + processCommonNetworkErrorString + "(t))\n", commonErrorEitherErrorTypeClassName, commonNetworkErrorsClassName)
            toErrorCodeBlockBuilder.add("                        }\n" +
              "                    }\n" +
              "                    else -> %T(%T." + processCommonNetworkErrorString + "(t))\n" +
              "                }\n" +
              "            }\n"
            , commonErrorEitherErrorTypeClassName, commonNetworkErrorsClassName)

            callObjectBuilder.addProperty(PropertySpec.builder("toError", throwableToErrorTypesLambda).initializer(toErrorCodeBlockBuilder.build()).build())

            //combined function

            val combinedFunction = FunSpec.builder("getCallAndErrorLambda")
              .addParameter("client", new ClassName(modelsNameSpace, className))


            parametersCache.toList.foreach(
              param => {
                combinedFunction.addParameter(param)
              }
            )
            combinedFunction.addStatement("return %T(client." + methodName + "(" + parametersCache.map({ _.getName }).mkString(",") +"), toError)" , apiNetworkCallResponseTypeClassName)


            callObjectBuilder.addFunction(combinedFunction.build())
            callObjectBuilder.addType(callErrorResopnseSealedClassBuilder.build())
            builder.addType(callObjectBuilder.build())
        }


        })
      }

      makeFile(className, builder)
    }

    def emptyCodeBlock(): CodeBlock = CodeBlock.builder().build()

    def generateJacksonObjectMapper(): File = {

      val moduleProperty = PropertySpec.builder("module", classOf[SimpleModule])
        .initializer("%T(%T(1, 0, 0, null, null, null))", classOf[SimpleModule], classOf[Version])
        .build()

      val deserializeInstant = FunSpec.builder("deserialize")
        .addParameter("jsonParser", classOf[JsonParser])
        .addParameter("deserializationContext", classOf[DeserializationContext])
        .returns(classOf[Instant])
        .addModifiers(KModifier.OVERRIDE)
        .addStatement("val value = jsonParser.valueAsString")
        .addStatement("return Instant.parse(value)")
        .build()
      val deserializerInstantType = TypeSpec.objectBuilder("InstantDeserializer")
        .superclass(ParameterizedTypeName.get(classOf[JsonDeserializer[Instant]], classOf[Instant]))
        .addFunction(deserializeInstant)
        .build()


      val serializeInstant = FunSpec.builder("serialize")
        .addParameter("value", classOf[Instant])
        .addParameter("jsonGenerator", classOf[JsonGenerator])
        .addParameter("serializerProvider", classOf[SerializerProvider])
        .addModifiers(KModifier.OVERRIDE)
        .addStatement("jsonGenerator.writeString(value.toString())")
        .build()
      val serializerInstantType = TypeSpec.objectBuilder("InstantSerializer")
        .superclass(ParameterizedTypeName.get(classOf[JsonSerializer[Instant]], classOf[Instant]))
        .addFunction(serializeInstant)
        .build()

      val deserializeLocalDate = FunSpec.builder("deserialize")
        .addParameter("jsonParser", classOf[JsonParser])
        .addParameter("deserializationContext", classOf[DeserializationContext])
        .returns(classOf[LocalDate])
        .addModifiers(KModifier.OVERRIDE)
        .addStatement("val value = jsonParser.valueAsString")
        .addStatement("return LocalDate.parse(value)")
        .build()
      val deserializerLocalDateType = TypeSpec.objectBuilder("LocalDateDeserializer")
        .superclass(ParameterizedTypeName.get(classOf[JsonDeserializer[LocalDate]], classOf[LocalDate]))
        .addFunction(deserializeLocalDate)
        .build()


      val serializeLocalDate = FunSpec.builder("serialize")
        .addParameter("value", classOf[LocalDate])
        .addParameter("jsonGenerator", classOf[JsonGenerator])
        .addParameter("serializerProvider", classOf[SerializerProvider])
        .addModifiers(KModifier.OVERRIDE)
        .addStatement("jsonGenerator.writeString(value.toString())")
        .build()
      val serializerLocalDateType = TypeSpec.objectBuilder("LocalDateSerializer")
        .superclass(ParameterizedTypeName.get(classOf[JsonSerializer[LocalDate]], classOf[LocalDate]))
        .addFunction(serializeLocalDate)
        .build()

      val className = sharedObjectMapperClassName
      val deserializationFeatureClassName = classOf[DeserializationFeature].getName
      val createCodeBlock = CodeBlock.builder()
        .addStatement("val mapper = com.fasterxml.jackson.databind.ObjectMapper()")
        .addStatement("mapper.registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule())")
        .addStatement("mapper.registerModule(com.fasterxml.jackson.datatype.joda.JodaModule())")
        .addStatement(s"mapper.configure(${deserializationFeatureClassName}.FAIL_ON_UNKNOWN_PROPERTIES, false)")
        .addStatement(s"mapper.configure(${deserializationFeatureClassName}.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)")
        .addStatement("module.addDeserializer(Instant::class.java, InstantDeserializer)")
        .addStatement("module.addSerializer(Instant::class.java, InstantSerializer)")
        .addStatement("module.addDeserializer(LocalDate::class.java, LocalDateDeserializer)")
        .addStatement("module.addSerializer(LocalDate::class.java, LocalDateSerializer)")
        .addStatement("mapper.registerModule(module)")
        .addStatement("return mapper")
        .build()
      val createFunSpec = FunSpec.builder("create")
        .addCode(createCodeBlock)
        .addModifiers(KModifier.PUBLIC)
        .returns(classOf[com.fasterxml.jackson.databind.ObjectMapper])
        .build()
      val builder = TypeSpec.objectBuilder(className)
        .addModifiers(KModifier.PUBLIC)
        .addKdoc(kdocClassMessage)
        .addProperty(moduleProperty)
        .addType(deserializerInstantType)
        .addType(serializerInstantType)
        .addType(deserializerLocalDateType)
        .addType(serializerLocalDateType)
        .addFunction(createFunSpec)
      makeFile(className, builder)
    }


    def generateErrorsHelper(): File = {

      val fileName = errorsHelperClassName

      //sealed class CommonNetworkErrors
      val commonNetworkErrorsBuilder = TypeSpec.classBuilder(commonNetworkErrorsClassName)
        .addModifiers(KModifier.PUBLIC, KModifier.SEALED)
        .addKdoc(s"Common generic errors that are expected to happen are not defined in apibuilder.json\n" + kdocClassMessage)

      commonNetworkHttpErrorsList.map({
        e => commonNetworkErrorsBuilder.addType(TypeSpec.objectBuilder(e._2).superclass(commonNetworkErrorsClassName).build())
      })

      commonNetworkErrorsBuilder.addType(TypeSpec.objectBuilder(serverTimeOutErrorClassName).superclass(commonNetworkErrorsClassName).build())
      commonNetworkErrorsBuilder.addType(TypeSpec.objectBuilder(serverUnknownErrorClassName).superclass(commonNetworkErrorsClassName).build())

      val processCommonFuncBody = CodeBlock.builder()
        .beginControlFlow("return when (t)")

           .add(
            "    is %T -> {\n" +
            "        val body: String? = t.response().errorBody()?.string()\n" +
            "        when (t.code()) {\n" +
            commonNetworkHttpErrorsList.map(e => "            " + e._1 + " -> " + e._2).mkString("\n") + "\n" +
            //"            500 -> ServerError\n" +
            "            else -> " + serverUnknownErrorClassName + "\n" +
            "        }\n" +
            "    }\n"+
            "    is %T -> " + serverTimeOutErrorClassName + "\n" +
            "    else -> " + serverUnknownErrorClassName + "\n"
            , classOf[com.jakewharton.retrofit2.adapter.rxjava2.HttpException], classOf[java.net.SocketTimeoutException])
        .endControlFlow()
        .build()

      commonNetworkErrorsBuilder.companionObject(TypeSpec.companionObjectBuilder()
          .addFunction(FunSpec.builder(processCommonNetworkErrorString)
              .addParameter(ParameterSpec.builder("t", getThrowableClassName()).build())
              .addCode(processCommonFuncBody)
              .returns(commonNetworkErrorsClassName)
            .build())

        build())


      //sealed class for Either Type
      val c = TypeVariableName.get("C", KModifier.OUT)
      val nothing = TypeVariableName.get("Nothing")


      val callErrorEitherType = TypeSpec.classBuilder(callErrorEitherErrorTypeClassName)
        .addModifiers(KModifier.DATA)
        .addTypeVariable(c)
        .primaryConstructor(FunSpec.constructorBuilder()
          .addParameter("error", c).build())
        .addProperty(PropertySpec.builder("error", c)
          .initializer("error")
          .build())
        .superclass(ParameterizedTypeName.get(eitherErrorTypeClassName, c))
        .build()

      val commonErrorEitherType = TypeSpec.classBuilder(commonErrorEitherErrorTypeClassName)
        .addModifiers(KModifier.DATA)
        .primaryConstructor(FunSpec.constructorBuilder()
          .addParameter("error", commonNetworkErrorsClassName).build())
        .addProperty(PropertySpec.builder("error", commonNetworkErrorsClassName)
          .initializer("error")
          .build())
        .superclass(ParameterizedTypeName.get(eitherErrorTypeClassName, nothing))
        .build()

      val eitherErrorBuilder = TypeSpec.classBuilder(eitherErrorTypeClassName)
        .addTypeVariable(c)
        .addModifiers(KModifier.SEALED)
        .addType(callErrorEitherType)
        .addType(commonErrorEitherType)
        .addKdoc(s"Either type that combines CommonNetworkErrors with network errors for a specific call (as defined in apibuilder.json)\n" + kdocClassMessage)



      //data class for ApiNetworkCallResponse Type
      val n = TypeVariableName.get("N")
      val e = TypeVariableName.get("E")

      val throwableTypeName = getThrowableClassName().asInstanceOf[TypeName]

      val singleParameterizedByN = ParameterizedTypeName.get(classToClassName(classOf[Single[Void]]), n)
      val throwableToELambda = LambdaTypeName.get(null, Array(throwableTypeName), e)
      val apiNetworkCallResponseBuilder = TypeSpec.classBuilder(apiNetworkCallResponseTypeClassName)
        .addModifiers(KModifier.PUBLIC, KModifier.DATA)
        .addTypeVariable(n)
        .addTypeVariable(e)
        .primaryConstructor(FunSpec.constructorBuilder()
          .addParameter("networkSingle", singleParameterizedByN)
          .addParameter("toError", throwableToELambda)
          .build())
        .addProperty(PropertySpec.builder("networkSingle", singleParameterizedByN)
          .initializer("networkSingle")
          .build())
        .addProperty(PropertySpec.builder("toError", throwableToELambda)
          .initializer("toError")
          .build())
        .addKdoc(s"Utility data class to combine a call and it's error responses\n" + kdocClassMessage)


      //output file
      val fileBuilder = FileSpec.builder(modelsNameSpace, fileName)
        .addType(apiNetworkCallResponseBuilder.build())
        .addType(eitherErrorBuilder.build())
        .addType(commonNetworkErrorsBuilder.build())
      makeFile(fileName, fileBuilder)
    }


    def generateEnums(enums: Seq[Enum]): Seq[File] = {
      enums.map(generateEnum(_))
    }

    def generateSourceFiles(service: Service): Seq[File] = {

      val generatedErrorsHelper = Seq(generateErrorsHelper())


      val generatedEnums = generateEnums(service.enums)

      val generatedUnionTypes = service.unions.map(generateUnionType(_))

      val generatedModels = service.models.map { model =>
        val relatedUnions = service.unions.filter(_.types.exists(_.`type` == model.name))
        generateModel(model, relatedUnions)
      }

      val generatedObjectMapper = Seq(generateJacksonObjectMapper())

      val generatedResources = service.resources.map(generateResource(_))

        generatedEnums ++
        generatedUnionTypes ++
        generatedModels ++
        generatedObjectMapper ++
        generatedErrorsHelper ++
        generatedResources
    }

    //write one file with a single class
    def makeFile(name: String, typeSpecBuilder: TypeSpec.Builder): File = {
      val typeSpec = typeSpecBuilder.build
      val kFile = FileSpec.get(modelsNameSpace, typeSpec)
      val sw = new StringWriter(1024)
      try {
        kFile.writeTo(sw)
        File(s"${name}.kt", Some(modelsDirectoryPath), sw.toString)
      } finally {
        sw.close()
      }
    }

    //write one file with multiple classes
    def makeFile(name: String, fileBuilder: FileSpec.Builder): File = {
      val sw = new StringWriter(1024)
      try {
        fileBuilder.build().writeTo(sw)
        File(s"${name}.kt", Some(modelsDirectoryPath), sw.toString)
      } finally {
        sw.close()
      }
    }

  }

}

object KotlinRxClasses extends KotlinGenerator
