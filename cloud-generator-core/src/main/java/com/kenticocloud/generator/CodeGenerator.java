/*
 * MIT License
 *
 * Copyright (c) 2017
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.kenticocloud.generator;

import com.google.common.base.CaseFormat;
import com.kenticocloud.delivery.*;
import com.squareup.javapoet.*;

import javax.lang.model.element.Modifier;
import java.io.File;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates java source files using the Kentico Cloud Type listing endpoint in the Delivery API
 */
public class CodeGenerator {

    private static final String DELIVERY_PACKAGE = "com.kenticocloud.delivery";
    private static final String JAVA_UTIL_PACKAGE = "java.util";

    String projectId;
    String packageName;
    File outputDir;

    /**
     * Constructs the CodeGenerator
     * @param projectId the project id from your Kentico Cloud account
     * @param packageName the package to place the generated models under
     * @param outputDir the source root to place the generated models
     * @throws UnsupportedOperationException when a there is a problem with the outputDir
     */
    public CodeGenerator(String projectId, String packageName, File outputDir) {
        this.projectId = projectId;
        this.packageName = packageName;
        this.outputDir = outputDir;
        if (!outputDir.exists() && !outputDir.mkdirs()){
            throw new UnsupportedOperationException(
                    String.format("Unable to create directory %s", outputDir.getAbsolutePath()));
        }
        if (!outputDir.isDirectory()) {
            throw new UnsupportedOperationException(
                    String.format("%s exists and is not a directory", outputDir.getAbsolutePath()));
        }
    }

    /**
     * Returns a list of specifications of the sources representing the types in your Kentico Cloud account
     * @return A list of specifications
     * @throws IOException when a problem occurs communicating with the Kentico Cloud API
     */
    public List<JavaFile> generateSources() throws IOException {
        return generateSources(new DeliveryClient(projectId));
    }

    /**
     * Returns a list of specifications of the sources representing the types in your Kentico Cloud account.
     * The provided {@link DeliveryClient} param is useful for testing, however in most environments, the default
     * {@link #generateSources()} method should suffice.
     * @param client A DeliveryClient instance to use to generate the sources.
     * @return A list of specifications
     * @throws IOException when a problem occurs communicating with the Kentico Cloud API
     */
    public List<JavaFile> generateSources(DeliveryClient client) throws IOException {
        return generateSources(client.getTypes().getTypes());
    }

    /**
     * Returns a list of specifications of the sources representing the types in your Kentico Cloud account.
     * The provided List of {@link ContentType} param is useful for testing, however in most environments, the default
     * {@link #generateSources()} method should generally be the only method invoking this.
     * @param types A List of ContentType to generate the sources from
     * @return A list of specifications
     */
    public List<JavaFile> generateSources(List<ContentType> types) {
        List<JavaFile> sources = new ArrayList<>();
        for (ContentType type : types) {
            sources.add(generateSource(type));
        }
        return sources;
    }

    /**
     * Returns a specification of the source representing this type in your Kentico Cloud account.
     * Invoking this directly may be useful for testing, however in most environments, the default
     * {@link #generateSources()} method should suffice.
     * @param type A ContentType to generate the source from
     * @return A specification
     */
    public JavaFile generateSource(ContentType type) {

        List<FieldSpec> fieldSpecs = new ArrayList<>();
        List<MethodSpec> methodSpecs = new ArrayList<>();

        for (Map.Entry<String, Element> element : type.getElements().entrySet()) {
            TypeName typeName = null;
            //Get the TypeName
            switch (element.getValue().getType()) {
                case "text" :
                case "rich_text" :
                case "url_slug" :
                    typeName = ClassName.get(String.class);
                    break;
                case "number" :
                    typeName = ClassName.get(Double.class);
                    break;
                case "multiple_choice" :
                    typeName = ParameterizedTypeName.get(
                            ClassName.get(JAVA_UTIL_PACKAGE, "List"),
                            ClassName.get(DELIVERY_PACKAGE, "Option"));
                    break;
                case "date_time" :
                    typeName = ClassName.get(ZonedDateTime.class);
                    break;
                case "asset" :
                    typeName = ParameterizedTypeName.get(
                            ClassName.get(JAVA_UTIL_PACKAGE, "List"),
                            ClassName.get(DELIVERY_PACKAGE, "Asset"));
                    break;
                case "modular_content" :
                    //It would be nice to inject a generated model here, but the information is not in the API
                    //consumer could always use .castTo() off the ContentItem
                    typeName = ClassName.get(ContentItem.class);
                    break;
                case "taxonomy" :
                    typeName = ParameterizedTypeName.get(
                            ClassName.get(JAVA_UTIL_PACKAGE, "List"),
                            ClassName.get(DELIVERY_PACKAGE, "Taxonomy"));
                    break;
                default :
                    break;
            }
            if (typeName != null) {
                //Add the field
                String fieldName = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, element.getKey());
                fieldSpecs.add(
                        FieldSpec.builder(typeName, fieldName)
                                .addAnnotation(
                                AnnotationSpec.builder(ElementMapping.class)
                                        .addMember("value", "$S", element.getKey())
                                        .build())
                                .build()
                );
                //Add the getter
                String getterName = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, "get_" + element.getKey());
                methodSpecs.add(
                        MethodSpec.methodBuilder(getterName)
                                .addModifiers(Modifier.PUBLIC)
                                .returns(typeName)
                                .addStatement("return $N", fieldName)
                                .build()
                );
                //Add the setter
                String setterName = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, "set_" + element.getKey());
                methodSpecs.add(
                        MethodSpec.methodBuilder(setterName)
                                .addModifiers(Modifier.PUBLIC)
                                .addParameter(typeName, fieldName)
                                .addStatement("this.$N = $N", fieldName, fieldName)
                                .build()
                );
            }
        }

        //Create the class
        TypeSpec.Builder typeSpecBuilder = TypeSpec
                .classBuilder(CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, type.getSystem().getCodename()))
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("This code was generated by a " +
                        "<a href=\"https://github.com/Kentico/cloud-generators-java\">cloud-generators-java tool</a>\n")
                .addJavadoc("\n")
                .addJavadoc("Changes to this file may cause incorrect behavior and will be lost if the code is regenerated.\n")
                .addJavadoc("For further modifications of the class, create a separate file and extend this class.\n")
                .addAnnotation(AnnotationSpec.builder(ContentItemMapping.class)
                        .addMember("value", "$S", type.getSystem().getCodename())
                        .build());

        //Add the fields
        for (FieldSpec fieldSpec : fieldSpecs) {
            typeSpecBuilder.addField(fieldSpec);
        }

        //Add the methods
        for (MethodSpec methodSpec : methodSpecs) {
            typeSpecBuilder.addMethod(methodSpec);
        }

        TypeSpec typeSpec = typeSpecBuilder.build();

        return JavaFile.builder(packageName, typeSpec).build();
    }

    /**
     * Writes the provided specifications to the outputDir provided in the constructor.  This is generally called
     * after a call to {@link #generateSources()}, but is separated in case you need to make modifications to your
     * specifications before writing.
     * @param sources A list of specifications
     * @throws IOException when a problem occurs writing the source files, note some source may have been written when
     * this is thrown
     */
    public void writeSources(List<JavaFile> sources) throws IOException {
        for (JavaFile source : sources) {
            writeSource(source);
        }
    }

    /**
     * Writes the provided specification to the outputDir provided in the constructor.  This is generally called
     * after a call to {@link #generateSources()}, but is separated in case you need to make modifications to your
     * specification before writing.
     * @param source A specification
     * @throws IOException when a problem occurs writing the source files
     */
    public void writeSource(JavaFile source) throws IOException {
        source.writeTo(outputDir);
    }
}
