/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.codehaus.groovy.transform;

import groovy.transform.MapConstructor;
import groovy.transform.TupleConstructor;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.classgen.VariableScopeVisitor;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.codehaus.groovy.ast.ClassHelper.make;
import static org.codehaus.groovy.ast.ClassHelper.makeWithoutCaching;
import static org.codehaus.groovy.ast.tools.GeneralUtils.args;
import static org.codehaus.groovy.ast.tools.GeneralUtils.assignS;
import static org.codehaus.groovy.ast.tools.GeneralUtils.block;
import static org.codehaus.groovy.ast.tools.GeneralUtils.callThisX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.callX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.constX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.copyStatementsWithSuperAdjustment;
import static org.codehaus.groovy.ast.tools.GeneralUtils.ctorX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.equalsNullX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.getAllProperties;
import static org.codehaus.groovy.ast.tools.GeneralUtils.getSetterName;
import static org.codehaus.groovy.ast.tools.GeneralUtils.ifElseS;
import static org.codehaus.groovy.ast.tools.GeneralUtils.ifS;
import static org.codehaus.groovy.ast.tools.GeneralUtils.params;
import static org.codehaus.groovy.ast.tools.GeneralUtils.propX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.stmt;
import static org.codehaus.groovy.ast.tools.GeneralUtils.throwS;
import static org.codehaus.groovy.ast.tools.GeneralUtils.varX;
import static org.codehaus.groovy.transform.ImmutableASTTransformation.createConstructorStatement;
import static org.codehaus.groovy.transform.ImmutableASTTransformation.makeImmutable;

/**
 * Handles generation of code for the @TupleConstructor annotation.
 */
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
public class TupleConstructorASTTransformation extends AbstractASTTransformation {

    static final Class MY_CLASS = TupleConstructor.class;
    static final ClassNode MY_TYPE = make(MY_CLASS);
    static final String MY_TYPE_NAME = "@" + MY_TYPE.getNameWithoutPackage();
    private static final ClassNode LHMAP_TYPE = makeWithoutCaching(LinkedHashMap.class, false);
    private static final ClassNode HMAP_TYPE = makeWithoutCaching(HashMap.class, false);
    private static final ClassNode CHECK_METHOD_TYPE = make(ImmutableASTTransformation.class);
    private static final Class<? extends Annotation> MAP_CONSTRUCTOR_CLASS = MapConstructor.class;
    private static final Map<Class<?>, Expression> primitivesInitialValues;

    static {
        final ConstantExpression zero = constX(0);
        final ConstantExpression zeroDecimal = constX(.0);
        primitivesInitialValues = new HashMap<Class<?>, Expression>();
        primitivesInitialValues.put(int.class, zero);
        primitivesInitialValues.put(long.class, zero);
        primitivesInitialValues.put(short.class, zero);
        primitivesInitialValues.put(byte.class, zero);
        primitivesInitialValues.put(char.class, zero);
        primitivesInitialValues.put(float.class, zeroDecimal);
        primitivesInitialValues.put(double.class, zeroDecimal);
        primitivesInitialValues.put(boolean.class, ConstantExpression.FALSE);
    }

    public void visit(ASTNode[] nodes, SourceUnit source) {
        init(nodes, source);
        AnnotatedNode parent = (AnnotatedNode) nodes[1];
        AnnotationNode anno = (AnnotationNode) nodes[0];
        if (!MY_TYPE.equals(anno.getClassNode())) return;

        if (parent instanceof ClassNode) {
            ClassNode cNode = (ClassNode) parent;
            if (!checkNotInterface(cNode, MY_TYPE_NAME)) return;
            boolean includeFields = memberHasValue(anno, "includeFields", true);
            boolean includeProperties = !memberHasValue(anno, "includeProperties", false);
            boolean includeSuperFields = memberHasValue(anno, "includeSuperFields", true);
            boolean includeSuperProperties = memberHasValue(anno, "includeSuperProperties", true);
            boolean callSuper = memberHasValue(anno, "callSuper", true);
            boolean force = memberHasValue(anno, "force", true);
            boolean defaults = !memberHasValue(anno, "defaults", false);
            boolean useSetters = memberHasValue(anno, "useSetters", true);
            boolean allProperties = memberHasValue(anno, "allProperties", true);
            List<String> excludes = getMemberStringList(anno, "excludes");
            List<String> includes = getMemberStringList(anno, "includes");
            boolean allNames = memberHasValue(anno, "allNames", true);
            if (!checkIncludeExcludeUndefinedAware(anno, excludes, includes, MY_TYPE_NAME)) return;
            if (!checkPropertyList(cNode, includes, "includes", anno, MY_TYPE_NAME, includeFields, includeSuperProperties, false, includeSuperFields)) return;
            if (!checkPropertyList(cNode, excludes, "excludes", anno, MY_TYPE_NAME, includeFields, includeSuperProperties, false, includeSuperFields)) return;
            Expression pre = anno.getMember("pre");
            if (pre != null && !(pre instanceof ClosureExpression)) {
                addError("Expected closure value for annotation parameter 'pre'. Found " + pre, cNode);
                return;
            }
            Expression post = anno.getMember("post");
            if (post != null && !(post instanceof ClosureExpression)) {
                addError("Expected closure value for annotation parameter 'post'. Found " + post, cNode);
                return;
            }

            createConstructor(this, cNode, includeFields, includeProperties, includeSuperFields, includeSuperProperties,
                    callSuper, force, excludes, includes, useSetters, defaults, allNames, allProperties,
                    sourceUnit, (ClosureExpression) pre, (ClosureExpression) post);

            if (pre != null) {
                anno.setMember("pre", new ClosureExpression(new Parameter[0], EmptyStatement.INSTANCE));
            }
            if (post != null) {
                anno.setMember("post", new ClosureExpression(new Parameter[0], EmptyStatement.INSTANCE));
            }
        }
    }

    public static void createConstructor(ClassNode cNode, boolean includeFields, boolean includeProperties, boolean includeSuperFields, boolean includeSuperProperties, boolean callSuper, boolean force, List<String> excludes, List<String> includes, boolean useSetters) {
        createConstructor(null, cNode, includeFields, includeProperties, includeSuperFields, includeSuperProperties, callSuper, force, excludes, includes, useSetters, true);
    }

    public static void createConstructor(AbstractASTTransformation xform, ClassNode cNode, boolean includeFields, boolean includeProperties, boolean includeSuperFields, boolean includeSuperProperties, boolean callSuper, boolean force, List<String> excludes, List<String> includes, boolean useSetters, boolean defaults) {
        createConstructor(xform, cNode, includeFields, includeProperties, includeSuperFields, includeSuperProperties, callSuper, force, excludes, includes, useSetters, defaults, false);
    }

    public static void createConstructor(AbstractASTTransformation xform, ClassNode cNode, boolean includeFields, boolean includeProperties, boolean includeSuperFields, boolean includeSuperProperties, boolean callSuper, boolean force, List<String> excludes, List<String> includes, boolean useSetters, boolean defaults, boolean allNames) {
        createConstructor(xform, cNode, includeFields, includeProperties, includeSuperFields, includeSuperProperties,
                callSuper, force, excludes, includes, useSetters, defaults, false, null, null, null);
    }

    public static void createConstructor(AbstractASTTransformation xform, ClassNode cNode, boolean includeFields,
                                         boolean includeProperties, boolean includeSuperFields, boolean includeSuperProperties,
                                         boolean callSuper, boolean force, List<String> excludes, final List<String> includes,
                                         boolean useSetters, boolean defaults, boolean allNames,
                                         SourceUnit sourceUnit, ClosureExpression pre, ClosureExpression post) {
        createConstructor(xform, cNode, includeFields, includeProperties, includeSuperFields, includeSuperProperties, callSuper, force, excludes, includes, useSetters, defaults, allNames,false, sourceUnit, pre, post);
    }

    public static void createConstructor(AbstractASTTransformation xform, ClassNode cNode, boolean includeFields,
                                         boolean includeProperties, boolean includeSuperFields, boolean includeSuperProperties,
                                         boolean callSuper, boolean force, List<String> excludes, final List<String> includes,
                                         boolean useSetters, boolean defaults, boolean allNames, boolean allProperties,
                                         SourceUnit sourceUnit, ClosureExpression pre, ClosureExpression post) {
        Set<String> names = new HashSet<String>();
        List<PropertyNode> superList;
        if (includeSuperProperties || includeSuperFields) {
            superList = getAllProperties(names, cNode.getSuperClass(), includeSuperProperties, includeSuperFields, false, allProperties, true, true);
        } else {
            superList = new ArrayList<PropertyNode>();
        }

        List<PropertyNode> list = getAllProperties(names, cNode, true, includeFields, false, allProperties, false, true);

        boolean makeImmutable = makeImmutable(cNode);
        boolean specialNamedArgCase = (ImmutableASTTransformation.isSpecialNamedArgCase(list, !defaults) && superList.isEmpty()) ||
                (ImmutableASTTransformation.isSpecialNamedArgCase(superList, !defaults) && list.isEmpty());

        // no processing if existing constructors found unless forced or ImmutableBase in play
        if (!cNode.getDeclaredConstructors().isEmpty() && !force && !makeImmutable) return;

        final List<Parameter> params = new ArrayList<Parameter>();
        final List<Expression> superParams = new ArrayList<Expression>();
        final BlockStatement preBody = new BlockStatement();
        boolean superInPre = false;
        if (pre != null) {
            superInPre = copyStatementsWithSuperAdjustment(pre, preBody);
            if (superInPre && callSuper) {
                xform.addError("Error during " + MY_TYPE_NAME + " processing, can't have a super call in 'pre' " +
                        "closure and also 'callSuper' enabled", cNode);
            }
        }
        final BlockStatement body = new BlockStatement();
        for (PropertyNode pNode : superList) {
            String name = pNode.getName();
            FieldNode fNode = pNode.getField();
            if (shouldSkipUndefinedAware(name, excludes, includes, allNames)) continue;
            params.add(createParam(fNode, name, defaults, xform, makeImmutable));
            boolean hasSetter = cNode.getProperty(name) != null && !fNode.isFinal();
            if (callSuper) {
                superParams.add(varX(name));
            } else if (!superInPre && !specialNamedArgCase) {
                if (makeImmutable) {
                    body.addStatement(createConstructorStatement(xform, cNode, pNode, false));
                } else {
                    if (useSetters && hasSetter) {
                        body.addStatement(stmt(callThisX(getSetterName(name), varX(name))));
                    } else {
                        body.addStatement(assignS(propX(varX("this"), name), varX(name)));
                    }
                }
            }
        }
        if (callSuper) {
            body.addStatement(stmt(ctorX(ClassNode.SUPER, args(superParams))));
        }
        if (!preBody.isEmpty()) {
            body.addStatements(preBody.getStatements());
        }

        for (PropertyNode pNode : list) {
            String name = pNode.getName();
            FieldNode fNode = pNode.getField();
            if (shouldSkipUndefinedAware(name, excludes, includes, allNames)) continue;
            Parameter nextParam = createParam(fNode, name, defaults, xform, makeImmutable);
            params.add(nextParam);
            boolean hasSetter = cNode.getProperty(name) != null && !fNode.isFinal();
            if (makeImmutable) {
                body.addStatement(createConstructorStatement(xform, cNode, pNode, false));
            } else {
                if (useSetters && hasSetter) {
                    body.addStatement(stmt(callThisX(getSetterName(name), varX(nextParam))));
                } else {
                    body.addStatement(assignS(propX(varX("this"), name), varX(nextParam)));
                }
            }
        }

        if (post != null) {
            body.addStatement(post.getCode());
        }

        if (includes != null) {
            Comparator<Parameter> includeComparator = new Comparator<Parameter>() {
                public int compare(Parameter p1, Parameter p2) {
                    return Integer.compare(includes.indexOf(p1.getName()), includes.indexOf(p2.getName()));
                }
            };
            Collections.sort(params, includeComparator);
        }

        boolean hasMapCons = hasAnnotation(cNode, MapConstructorASTTransformation.MY_TYPE);
        if (!specialNamedArgCase || !hasMapCons) {
            cNode.addConstructor(new ConstructorNode(ACC_PUBLIC, params.toArray(new Parameter[params.size()]), ClassNode.EMPTY_ARRAY, body));
        }

        if (sourceUnit != null && !body.isEmpty()) {
            VariableScopeVisitor scopeVisitor = new VariableScopeVisitor(sourceUnit);
            scopeVisitor.visitClass(cNode);
        }

        // If the first param is def or a Map, named args might not work as expected so we add a hard-coded map constructor in this case
        // we don't do it for LinkedHashMap for now (would lead to duplicate signature)
        // or if there is only one Map property (for backwards compatibility)
        // or if there is already a @MapConstructor annotation
        if (!params.isEmpty() && defaults && !hasMapCons && specialNamedArgCase) {
            ClassNode firstParamType = params.get(0).getType();
            if (params.size() > 1 || firstParamType.equals(ClassHelper.OBJECT_TYPE)) {
                String message = "The class " + cNode.getName() + " was incorrectly initialized via the map constructor with null.";
                addSpecialMapConstructors(cNode, false, message);
            }
        }
    }

    private static Parameter createParam(FieldNode fNode, String name, boolean defaults, AbstractASTTransformation xform, boolean makeImmutable) {
        Parameter param = new Parameter(fNode.getType(), name);
        if (defaults) {
            param.setInitialExpression(providedOrDefaultInitialValue(fNode));
        } else if (!makeImmutable) {
            // TODO we could support some default vals provided they were listed last
            if (fNode.getInitialExpression() != null) {
                xform.addError("Error during " + MY_TYPE_NAME + " processing, default value processing disabled but default value found for '" + fNode.getName() + "'", fNode);
            }
        }
        return param;
    }

    private static Expression providedOrDefaultInitialValue(FieldNode fNode) {
        Expression initialExp = fNode.getInitialExpression() != null ? fNode.getInitialExpression() : ConstantExpression.NULL;
        final ClassNode paramType = fNode.getType();
        if (ClassHelper.isPrimitiveType(paramType) && initialExp.equals(ConstantExpression.NULL)) {
            initialExp = primitivesInitialValues.get(paramType.getTypeClass());
        }
        return initialExp;
    }

    public static void addSpecialMapConstructors(ClassNode cNode, boolean addNoArg, String message) {
        Parameter[] parameters = params(new Parameter(LHMAP_TYPE, "__namedArgs"));
        BlockStatement code = new BlockStatement();
        VariableExpression namedArgs = varX("__namedArgs");
        namedArgs.setAccessedVariable(parameters[0]);
        code.addStatement(ifElseS(equalsNullX(namedArgs),
                illegalArgumentBlock(message),
                processArgsBlock(cNode, namedArgs)));
        ConstructorNode init = new ConstructorNode(ACC_PUBLIC, parameters, ClassNode.EMPTY_ARRAY, code);
        cNode.addConstructor(init);
        // potentially add a no-arg constructor too
        if (addNoArg) {
            code = new BlockStatement();
            code.addStatement(stmt(ctorX(ClassNode.THIS, ctorX(LHMAP_TYPE))));
            init = new ConstructorNode(ACC_PUBLIC, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, code);
            cNode.addConstructor(init);
        }
    }

    private static BlockStatement illegalArgumentBlock(String message) {
        return block(throwS(ctorX(make(IllegalArgumentException.class), args(constX(message)))));
    }

    private static BlockStatement processArgsBlock(ClassNode cNode, VariableExpression namedArgs) {
        BlockStatement block = new BlockStatement();
        for (PropertyNode pNode : cNode.getProperties()) {
            if (pNode.isStatic()) continue;

            // if namedArgs.containsKey(propertyName) setProperty(propertyName, namedArgs.get(propertyName));
            Statement ifStatement = ifS(
                    callX(namedArgs, "containsKey", constX(pNode.getName())),
                    assignS(varX(pNode), propX(namedArgs, pNode.getName())));
            block.addStatement(ifStatement);
        }
        block.addStatement(stmt(callX(CHECK_METHOD_TYPE, "checkPropNames", args(varX("this"), namedArgs))));
        return block;
    }
}
