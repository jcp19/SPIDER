import com.ibm.wala.classLoader.Language;
import com.ibm.wala.core.tests.slicer.SlicerTest;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.slicer.Slicer;
import com.ibm.wala.ipa.slicer.Slicer.ControlDependenceOptions;
import com.ibm.wala.ipa.slicer.Slicer.DataDependenceOptions;
import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.util.config.AnalysisScopeReader;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;

    public class MsgAnalysis {
        public static void main(String[] args) throws IOException {
            File exclusions = new File("/home/joao/Code/minha/exemplos/exclusions.txt");
            AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope("/home/joao/Code/minha/exemplos/classpath/",exclusions);
            try {
                ClassHierarchy cha = ClassHierarchyFactory.make(scope);
                Iterable<Entrypoint> entrypoints = Util.makeMainEntrypoints(scope, cha);
                AnalysisOptions options = new AnalysisOptions(scope, entrypoints);

                // https://stackoverflow.com/questions/36188478/wala-call-graph?utm_medium=organic&utm_source=google_rich_qa&utm_campaign=google_rich_qa
                //options.setReflectionOptions(ReflectionOptions.NONE);
                CallGraphBuilder<InstanceKey> builder =
                        Util.makeZeroOneCFABuilder(Language.JAVA, options, new AnalysisCacheImpl(), cha, scope);

                CallGraph cg = builder.makeCallGraph(options, null);
                PointerAnalysis pa = builder.getPointerAnalysis();

                Statement statement = SlicerTest.findCallTo(SlicerTest.findMainMethod(cg), "succ2");

                // Print instructions
                for (Iterator<? extends CGNode> it = cg.getSuccNodes(cg.getFakeRootNode()); it.hasNext();) {
                    CGNode n = it.next();
                    System.out.println(n);
                }

                IR ir = SlicerTest.findMainMethod(cg).getIR();
                for (Iterator<SSAInstruction> it = ir.iterateAllInstructions(); it.hasNext();) {
                    SSAInstruction s = it.next();
                    //if (s instanceof com.ibm.wala.ssa.SSAAbstractInvokeInstruction) {
                    //  com.ibm.wala.ssa.SSAAbstractInvokeInstruction call = (com.ibm.wala.ssa.SSAAbstractInvokeInstruction) s;
                    System.out.println("Call:" + s);
                    //}
                }

                // Context-sensitive thin sliceOneOne
                System.out.println("Backward slice:");
                Collection<Statement> backwardSlice = Slicer.computeBackwardSlice(statement, cg, pa, DataDependenceOptions.NO_BASE_PTRS, ControlDependenceOptions.NONE);
                SlicerTest.dumpSlice(backwardSlice);

                System.out.println("Forward slice");
                Collection<Statement> forwardSlice = Slicer.computeForwardSlice(statement, cg, pa, DataDependenceOptions.NO_BASE_PTRS, ControlDependenceOptions.NONE);
                SlicerTest.dumpSlice(forwardSlice);
                //SDG<InstanceKey> sdg = new SDG<>(cg, builder.getPointerAnalysis(), DataDependenceOptions.NO_BASE_PTRS, ControlDependenceOptions.NONE);
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

