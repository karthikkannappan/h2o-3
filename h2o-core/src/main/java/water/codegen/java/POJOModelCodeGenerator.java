package water.codegen.java;

import org.joda.time.DateTime;

import java.util.ArrayList;

import hex.Model;
import hex.ModelMetrics;
import hex.genmodel.GenModel;
import water.Key;
import water.codegen.CodeGenerator;
import water.codegen.JCodeSB;
import water.codegen.java.mixins.ModelMixin;

import static java.lang.reflect.Modifier.FINAL;
import static java.lang.reflect.Modifier.PRIVATE;
import static java.lang.reflect.Modifier.PUBLIC;
import static java.lang.reflect.Modifier.STATIC;
import static water.codegen.java.JCodeGenUtil.VALUE;
import static water.codegen.java.JCodeGenUtil.ctor;
import static water.codegen.java.JCodeGenUtil.field;
import static water.codegen.java.JCodeGenUtil.klazz;
import static water.codegen.java.JCodeGenUtil.s;
import static water.codegen.java.JCodeGenUtil.toJavaId;

/**
 * Model generator:
 *  -  can generates multiple classes (compilation units)
 *
 *
 *  - preview can just generate top-level compilation unit
 *
 *  FIXME: all methods field/method/class should be generalized and definable (POJOModelCodeGenerator
 *  shoudl accept their implementation)
 *  FIXME: why model container is not CodeGeneratorPipeline
 *  FIXME: build should be protected against multiple build
 */
abstract public class POJOModelCodeGenerator<S extends POJOModelCodeGenerator<S, M>, M extends Model<M, ?, ?>>
    extends JavaCodeGenerator<S, M> {

  protected final M model;
  private String packageName;

  protected POJOModelCodeGenerator(M model) {
    this.model = model;
  }

  public S withPackage(String packageName) {
    this.packageName = packageName;
    return self();
  }

  public S withCompilationUnit(CompilationUnitGenerator cug) {
    add(cug);
    cug.setMcg(this);
    return self();
  }

  /** Create initial top-level model compilation unit. */
  protected CompilationUnitGenerator createModelCu() {
    CodeGenerator commentGenerator = new CodeGenerator() {
      @Override
      public void generate(JCodeSB out) {
        out
            .p("/*").ii(2).nl()
            .p("Licensed under the Apache License, Version 2.0").nl()
            .p("  http://www.apache.org/licenses/LICENSE-2.0.html").nl()
            .nl()
            .p("AUTOGENERATED BY H2O at ").p(new DateTime().toString()).nl()
            .p("H2O v").p(water.H2O.ABV.projectVersion()).p(" (").p(water.H2O.ABV.describe()).p(")").di(2).nl()
            .p("*/").nl();
      }
    };
    return new CompilationUnitGenerator(packageName, getModelName())
        .withComment(commentGenerator)
        .withPackageImport("java.util.Map",
                           "hex.genmodel.GenModel",
                           "hex.genmodel.annotations.ModelPojo");

  }

  /** Create initial class generator
   * for model filling all fields by defaults.
   */
  protected ClassCodeGenerator createModelClass(CompilationUnitGenerator cucg) {
    // Build a model class generator by composing small pieces
    final String modelName = getModelName();
    // Create a klass generator and prepare method generators for all abstract class in GenModel
    ClassCodeGenerator ccg = klazz(modelName, GenModel.class, model)
        .withMixin(model, true, true, ModelMixin.class)
        .withModifiers(PUBLIC)
        .withAnnotation(s("@ModelPojo(name=\"").p(getModelName()).p("\", algorithm=\"").p(getAlgoName()).p("\")"))
        .withCtor(
            ctor(modelName) // FIXME: this is repeated and can be derived from context
              .withModifiers(PUBLIC) // FIXME: Adopt Scala strategy - everything is public if not denied
              .withBody(s("super(NAMES, DOMAINS);"))
        );

    // Need to update names generation
    ccg.field("NAMES").withValue(VALUE(model._output._names, 0, model._output.nfeatures()));

    return ccg;
  }

  public final S build() {
    // Shared work by all generators
    CompilationUnitGenerator cug = createModelCu().build();
    ClassCodeGenerator cg = createModelClass(cug).build();

    // FIXME: reverse initialization order with setup link: cug->mcg a pak cg->cug
    this.withCompilationUnit(cug.withClassGenerator(cg));

    // Reimplement in model-specific subclasss
    buildImpl(cug, cg);

    // At the end call all defined generators build method
    return super.build();
  }

  /**
   * The method which should be implemented by a corresponding model generator.
   *
   * @param cucg compilation unit generator
   * @param ccg  model class generator, it already predefines full model generation, but
   *             a corresponding model generator can redefine all slots.
   * @return  self
   */
  abstract protected S buildImpl(CompilationUnitGenerator cucg, ClassCodeGenerator ccg);

  String getModelName() {
    return toJavaId(model._key.toString());
  }

  String getAlgoName() {
    return model.getClass().getSimpleName().toLowerCase().replace("model", "");
  }

  @Override
  public void add(ClassCodeGenerator ccg) {
    CompilationUnitGenerator cu = new CompilationUnitGenerator(this.packageName, ccg.name);
    ccg.modifiers &= ~(STATIC | PRIVATE); // Remove illegal modifiers from top-level classes
    cu.withClassGenerator(ccg);
    withCompilationUnit(cu);
  }

  final public ClassGenContainer classContainer(CodeGenerator caller) {
    // FIXME this should be driven by a strategy
    // (1) forward here, (2) forward to encapsulating top-level class
    return this;
  }

  /* FIXME: how to define O ????
  public <O extends Model.Output> O modelOutput() {
    return model._output;
  }*/

  /** Create a new field generator */
  public FieldCodeGenerator FIELD(double[] fieldValue, String fieldName) {
    return JCodeGenUtil.field(double[].class, fieldName)
        .withModifiers(PUBLIC | STATIC | FINAL)
        .withValue(VALUE(fieldValue));
  }

  public FieldCodeGenerator FIELD(int[] fieldValue, String fieldName) {
    return JCodeGenUtil.field(int[].class, fieldName)
        .withModifiers(PUBLIC | STATIC | FINAL)
        .withValue(VALUE(fieldValue));
  }
}


