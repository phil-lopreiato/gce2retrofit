package com.sqisland.gce2retrofit;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.squareup.javawriter.JavaWriter;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.text.WordUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static javax.lang.model.element.Modifier.PUBLIC;

public class Generator {
  private static final String OPTION_CLASS_MAP = "classmap";
  private static final String OPTION_METHODS = "methods";

  private static Gson gson = new Gson();

  public enum MethodType {
    SYNC, ASYNC
  }

  public static void main(String... args)
      throws IOException, URISyntaxException {
    Options options = getOptions();


    CommandLine cmd = getCommandLine(options, args);
    if (cmd == null) {
      return;
    }

    String[] arguments = cmd.getArgs();
    if (arguments.length != 2) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("java -jar gce2retrofit.jar discovery.json output_dir", options);
      System.exit(1);
    }

    String discoveryFile = arguments[0];
    String outputDir = arguments[1];

    Map<String, String> classMap = cmd.hasOption(OPTION_CLASS_MAP)?
        readClassMap(new FileReader(cmd.getOptionValue(OPTION_CLASS_MAP))) : null;

    EnumSet<MethodType> methodTypes = EnumSet.noneOf(MethodType.class);
    if (cmd.hasOption(OPTION_METHODS)) {
      String[] parts = cmd.getOptionValue(OPTION_METHODS).split(",");
      for (String part : parts) {
        if ("sync".equals(part) || "both".equals(part)) {
          methodTypes.add(MethodType.SYNC);
        }
        if ("async".equals(part) || "both".equals(part)) {
          methodTypes.add(MethodType.ASYNC);
        }
      }
    }
    if (methodTypes.isEmpty()) {
      methodTypes = EnumSet.allOf(MethodType.class);
    }

    generate(new FileReader(discoveryFile), new FileWriterFactory(outputDir),
        classMap, methodTypes);
  }

  private static Options getOptions() {
    Options options = new Options();
    options.addOption(
        OPTION_CLASS_MAP, true, "Map fields to classes. Format: field_name\\tclass_name");
    options.addOption(
        OPTION_METHODS, true,
        "Methods to generate, either sync or async. Default is to generate both.");
    return options;
  }

  private static CommandLine getCommandLine(Options options, String... args) {
    CommandLineParser parser = new BasicParser();
    try {
      CommandLine cmd = parser.parse(options, args);
      return cmd;
    } catch (ParseException e) {
      System.out.println("Unexpected exception:" + e.getMessage());
    }
    return null;
  }

  public static void generate(
      Reader discoveryReader, WriterFactory writerFactory,
      Map<String, String> classMap, EnumSet<MethodType> methodTypes)
      throws IOException, URISyntaxException {
    JsonReader jsonReader = new JsonReader(discoveryReader);

    Discovery discovery = gson.fromJson(jsonReader, Discovery.class);

    String packageName = StringUtil.getPackageName(discovery.baseUrl);
    String modelPackageName = packageName + ".model";

    for (Entry<String, JsonElement> entry : discovery.schemas.entrySet()) {
      generateModel(
          writerFactory, modelPackageName, entry.getValue().getAsJsonObject(), classMap);
    }

    for (Entry<String, JsonElement> entry : discovery.resources.entrySet()) {
      generateInterface(writerFactory, packageName, entry, methodTypes);
    }
  }

  public static Map<String, String> readClassMap(Reader reader) throws IOException {
    Map<String, String> classMap = new HashMap<String, String>();

    String line;
    BufferedReader bufferedReader = new BufferedReader(reader);
    while ((line = bufferedReader.readLine()) != null) {
      String[] fields = line.split("\t");
      if (fields.length == 2) {
        classMap.put(fields[0], fields[1]);
      }
    }

    return classMap;
  }

  private static void generateModel(
      WriterFactory writerFactory, String modelPackageName,
      JsonObject schema, Map<String, String> classMap)
      throws IOException {
    String id = schema.get("id").getAsString();

    String path = StringUtil.getPath(modelPackageName, id + ".java");
    Writer writer = writerFactory.getWriter(path);
    JavaWriter javaWriter = new JavaWriter(writer);

    javaWriter.emitPackage(modelPackageName)
        .emitImports("java.util.List")
        .emitEmptyLine();

    javaWriter.beginType(modelPackageName + "." + id, "class", EnumSet.of(PUBLIC));

    JsonObject properties = schema.get("properties").getAsJsonObject();
    for (Entry<String, JsonElement> entry : properties.entrySet()) {
      String key = entry.getKey();
      PropertyType propertyType = gson.fromJson(
          entry.getValue(), PropertyType.class);
      String javaType = propertyType.toJavaType();
      if (classMap != null && classMap.containsKey(key)) {
        javaType = classMap.get(key);
      }
      javaWriter.emitField(javaType, key, EnumSet.of(PUBLIC));
    }

    javaWriter.endType();

    writer.close();
  }

  private static void generateInterface(
      WriterFactory writerFactory, String packageName, Entry<String, JsonElement> resource,
      EnumSet<MethodType> methodTypes)
      throws IOException {
    String resourceName = resource.getKey();
    String capitalizedName = WordUtils.capitalizeFully(resourceName, '_');
    String className = capitalizedName.replaceAll("_", "");

    String path = StringUtil.getPath(packageName, className + ".java");
    Writer fileWriter = writerFactory.getWriter(path);
    JavaWriter javaWriter = new JavaWriter(fileWriter);

    javaWriter.emitPackage(packageName)
        .emitImports(packageName + ".model.*")
        .emitEmptyLine()
        .emitImports(
            "retrofit.Callback",
            "retrofit.http.GET",
            "retrofit.http.POST",
            "retrofit.http.PATCH",
            "retrofit.http.DELETE",
            "retrofit.http.Body",
            "retrofit.http.Path",
            "retrofit.http.Query")
        .emitEmptyLine();

    javaWriter.beginType(
        packageName + "." + className, "interface", EnumSet.of(PUBLIC));

    JsonObject methods = resource.getValue().getAsJsonObject()
        .get("methods").getAsJsonObject();
    for (Entry<String, JsonElement> entry : methods.entrySet()) {
      String methodName = entry.getKey();
      Method method = gson.fromJson(entry.getValue(), Method.class);

      if (methodTypes.contains(MethodType.SYNC)) {
        javaWriter.emitAnnotation(method.httpMethod, "\"/" + method.path + "\"");
        emitMethodSignature(fileWriter, methodName, method, true);
      }

      if (methodTypes.contains(MethodType.ASYNC)) {
        javaWriter.emitAnnotation(method.httpMethod, "\"/" + method.path + "\"");
        emitMethodSignature(fileWriter, methodName, method, false);
      }
    }

    javaWriter.endType();

    fileWriter.close();
  }

  // TODO: Use JavaWriter to emit method signature
  private static void emitMethodSignature(
      Writer writer, String methodName, Method method, boolean synchronous) throws IOException {
    ArrayList<String> params = new ArrayList<String>();

    if (method.request != null) {
      params.add("@Body " + method.request.$ref + " " +
          method.request.parameterName);
    }
    for (Entry<String, JsonElement> param : getParams(method)) {
      params.add(param2String(param));
    }

    String returnValue = (synchronous && method.response != null) ? method.response.$ref : "void";

    if (!synchronous) {
      if (method.response == null) {
        params.add("Callback<Void> cb");
      } else {
        params.add("Callback<" + method.response.$ref + "> cb");
      }
    }

    writer.append("  " + returnValue + " " + methodName + "(");
    for (int i = 0; i < params.size(); ++i) {
      if (i != 0) {
        writer.append(", ");
      }
      writer.append(params.get(i));
    }

    writer.append(");\n");
  }

  /**
   * Assemble a list of parameters, with the first entries matching the ones
   * listed in parameterOrder
   *
   * @param method The method containing parameters and parameterOrder
   * @return Ordered parameters
   */
  private static List<Entry<String, JsonElement>> getParams(Method method) {
    List<Entry<String, JsonElement>> params
        = new ArrayList<Entry<String, JsonElement>>();
    if (method.parameters == null) {
      return params;
    }

    // Convert the entry set into a map, and extract the keys not listed in
    // parameterOrder
    HashMap<String, Entry<String, JsonElement>> map
        = new HashMap<String, Entry<String, JsonElement>>();
    List<String> remaining = new ArrayList<String>();
    for (Entry<String, JsonElement> entry : method.parameters.entrySet()) {
      String key = entry.getKey();
      map.put(key, entry);
      if (method.parameterOrder == null ||
          !method.parameterOrder.contains(key)) {
        remaining.add(key);
      }
    }

    // Add the keys in parameterOrder
    if (method.parameterOrder != null) {
      for (String key : method.parameterOrder) {
        params.add(map.get(key));
      }
    }

    // Then add the keys not in parameterOrder
    for (String key : remaining) {
      params.add(map.get(key));
    }

    return params;
  }

  private static String param2String(Entry<String, JsonElement> param) {
    StringBuffer buf = new StringBuffer();
    String paramName = param.getKey();
    ParameterType paramType = gson.fromJson(
        param.getValue(), ParameterType.class);
    if ("path".equals(paramType.location)) {
      buf.append("@Path(\"" + paramName + "\") ");
    }
    if ("query".equals(paramType.location)) {
      buf.append("@Query(\"" + paramName + "\") ");
    }

    String type = paramType.toJavaType();
    if (!paramType.required) {
      type = StringUtil.primitiveToObject(type);
    }
    buf.append(type + " " + paramName);

    return buf.toString();
  }

  private static class FileWriterFactory implements WriterFactory {
    private final String parentDir;

    public FileWriterFactory(String parentDir) {
      this.parentDir = parentDir;
    }

    @Override
    public Writer getWriter(String path) throws IOException {
      File fullPath = new File(parentDir,  path);
      File dir = new File(fullPath.getParent());
      if (!dir.exists()) {
        dir.mkdirs();
      }
      return new FileWriter(fullPath);
    }
  }
}