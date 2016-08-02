package water.codegen.java;

import java.util.Stack;

import hex.tree.CompressedTree;
import hex.tree.DHistogram;
import hex.tree.TreeVisitor;
import water.codegen.JCodeSB;
import water.codegen.SB;
import water.codegen.java.mixins.TreeMixin;
import water.util.IcedBitSet;

import static java.lang.reflect.Modifier.FINAL;
import static java.lang.reflect.Modifier.PUBLIC;
import static java.lang.reflect.Modifier.STATIC;

/** A tree code generator producing Java code representation of the tree:
 *
 *  - A generated class contains score0 method
 *  - if score0 method is too long, it redirects prediction to a new subclass's score0 method
 */
class TreePOJOCodeGenVisitor extends TreeVisitor<RuntimeException> {

  // Class names used for code generation
  final String _javaClassName;
  // Names of columns used for split decisions
  final String[] _columnNames;
  // Top level container which holds generated classes
  final ClassGenContainer _classContainer;

  // Tree walking stack to remember state
  private final Stack<TreeCodeGenState> _stack = new Stack<>();
  // Actual state of code generator
  private TreeCodeGenState _state;

  // Number of generated subtrees
  int _subtrees = 0;

  public TreePOJOCodeGenVisitor(String[] columnNames, CompressedTree ct, ClassGenContainer classContainer, String javaClassName) {
    super(ct);
    _columnNames = columnNames;
    _classContainer = classContainer;
    _javaClassName = javaClassName;
  }

  /**
   * Entry point for subtree traversal.
   *
   * It initializes generator state.
   *
   * @param subtree
   * @return
   * @throws RuntimeException
   */
  protected TreeCodeGenState entry(int subtree) throws RuntimeException {
    // Save actual state on top of stack when entering new state
    if (_state != null) {
      _state.depth = _depth;
      _stack.push(_state);
    }
    String treeClassName = _javaClassName + (subtree > 0 ? "_" + String.valueOf(subtree) : "");

    TreeCodeGenState state = new TreeCodeGenState();
    ClassCodeGenerator treeCcg = new ClassCodeGenerator(treeClassName).withMixin(TreeMixin.class);
    _classContainer.add(treeCcg);
    state.treeCcg = treeCcg;
    state.body.ip("double pred = ");
    return state;
  }

  /**
   * Exit point for subtree traversal.
   *
   * @throws RuntimeException
   */
  protected TreeCodeGenState exit() throws RuntimeException {
    _state.body.p(";").nl();
    _state.body.ip("return pred;").p(" // ").p(_state.toString());
    _state.body.nl().di(1);
    _state.treeCcg.method("score0").withBody(_state.body);
    return _stack.isEmpty() ? null : _stack.pop();
  }

  /**
   * Look at the stack of processed nodes
   * and check if there is a node saved at actual depth.
   *
   * @return true if node on the top of stack was saved at current depth
   */
  protected boolean isExitPoint() {
    return !_stack.empty() && _stack.peek().depth == _depth;
  }

  @Override protected void pre(int col, float fcmp, IcedBitSet gcmp, int equal, DHistogram.NASplitDir naSplitDir) {
    // Check for method size and number of constants generated in constant pool
    if (_state.isBigEnough()) {
      // Offload computation to newly generated class
      _state.body.p(_javaClassName).p('_').p(_subtrees).p(".score0").p("(data)");
      // Save and entry new state of tree generation
      _state = entry(_subtrees++);
    }
    // Generates array for group splits
    if (equal == 2 || equal == 3 && gcmp != null) {
    }
    // Generates decision
    _state.body.ip(" (");
    if (equal == 0 || equal == 1) {
      if (naSplitDir == DHistogram.NASplitDir.NAvsREST) _state.body.p("!Double.isNaN(data[").p(col).p("])");
      else if (naSplitDir == DHistogram.NASplitDir.NALeft || naSplitDir == DHistogram.NASplitDir.Left) _state.body.p("Double.isNaN(data[").p(col).p("]) || ");
      else if (equal==1) _state.body.p("!Double.isNaN(data[").p(col).p("]) && ");
      if (naSplitDir != DHistogram.NASplitDir.NAvsREST) {
        _state.body.p("data[").p(col);
        // Generate column names only if necessary
        _state.body.p(" /* ").p(_columnNames[col]).p(" */");
        _state.body.p("] ").p(equal == 1 ? "!= " : "< ").pj(fcmp); // then left and then right (left is !=)
        _state.javaConstantPoolSize += 2; // * bytes for generated float which is represented as double because of cast (Double occupies 2 slots in constant pool)
      }
    } else { // It is group split
      if (naSplitDir == DHistogram.NASplitDir.NAvsREST ) {
        _state.body.p("!Double.isNaN(data[").p(col).p("])"); //no need to store group split, all we need to know is NA or not
      } else {
        if (naSplitDir == DHistogram.NASplitDir.NALeft || naSplitDir == DHistogram.NASplitDir.Left) {
          _state.body.p("Double.isNaN(data[").p(col).p("]) || "); //NAs go left
        }
        String groupSplitFieldName = "GRPSPLIT" + _state.grpSplitCnt++;
        // Generate field in the current class
        _state.treeCcg.withField(
            new FieldCodeGenerator(groupSplitFieldName)
                .withComment(gcmp.toString())
                .withModifiers(PUBLIC | STATIC | FINAL)
                .withType(byte[].class)
                .withValue(new SB().p(gcmp.toStrArray()))
        );
        // Update size of generated bytecode
        _state.javaConstantPoolSize += gcmp.numBytes() + 3; // Each byte stored in split (NOT TRUE) and field reference and field name (Utf8) and NameAndType
        _state.javaStaticInitSize += 6 + gcmp.numBytes() * 6; // byte size of instructions to create an array and load all byte values (upper bound = dup, bipush, bipush, bastore = 5bytes)
        // Generate call into current method body
        gcmp.toJava(_state.body, groupSplitFieldName, col, _columnNames[col]);
      }
    }
    _state.body.p(" ? ").ii(1).nl();
    _state.nodesCount++;
  }
  @Override protected void leaf( float pred  ) {
    _state.body.i().pj(pred);
    // We are generating float which occupies single slot in constant pool, however
    // left side of final expression is double, hence javac directly stores double in constant pool (2places)
    _state.javaConstantPoolSize += 2;
  }

  @Override
  protected void mid(int col, float fcmp, int equal) throws RuntimeException {
    _state.body.p(" : ").nl();
  }

  @Override protected void post(int col, float fcmp, int equal ) {
    _state.body.p(')').di(1);
    if (isExitPoint()) {
      _state = exit();
    }
  }

  public void build() {
    // Create initial state
    _state = entry(_subtrees++);
    visit();
    exit();
  }

  private class TreeCodeGenState {
    // Limit for a number decision nodes visited per generated class
    private static final int MAX_NODES = (1 << 12) / 4;
    // Keep some space for method and string constants - max number of items in constant pool
    private static final int MAX_CONSTANT_POOL_SIZE = (1 << 16) - 4096;
    // Max size of static initalizer in bytes
    private static final int MAX_METHOD_SIZE = (1 << 16) - 4096;

    // Number of visited nodes
    int nodesCount;
    // Number of generated group splits
    int grpSplitCnt;
    // Number of items generated into constant pool
    int javaConstantPoolSize;
    // Byte size of class static initializer
    int javaStaticInitSize;
    // Actual code generator
    ClassCodeGenerator treeCcg;
    // Actual score method body
    JCodeSB body = new SB();
    // Depth of reached state when pushed onto stack
    int depth;

    boolean isBigEnough() {
      return nodesCount > MAX_NODES
             || javaConstantPoolSize > MAX_CONSTANT_POOL_SIZE
             || javaStaticInitSize > MAX_METHOD_SIZE;
    }

    @Override
    public String toString() {
      return "nodesCount=" + nodesCount +
             ", grpSplitCnt=" + grpSplitCnt +
             ", javaConstantPoolSize=" + javaConstantPoolSize +
             ", javaStaticInitSize=" + javaStaticInitSize + 'B';
    }
  }
}

