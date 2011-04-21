/*******************************************************************************
 * Copyright (c) 2011 Codehaus.org, SpringSource, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *      Andrew Eisenberg - Initial implemenation
 *******************************************************************************/
package org.codehaus.groovy.eclipse.dsl.contributions;

import groovy.lang.Closure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.eclipse.GroovyLogManager;
import org.codehaus.groovy.eclipse.TraceCategory;
import org.codehaus.groovy.eclipse.dsl.lookup.ResolverCache;
import org.codehaus.groovy.eclipse.dsl.pointcuts.BindingSet;
import org.codehaus.groovy.eclipse.dsl.pointcuts.GroovyDSLDContext;
import org.eclipse.jdt.groovy.search.VariableScope;

/**
 * A contribution group will determine the set of contribution elements (eg-
 * extra methods, properties, templates, etc) that are added to a particular type
 * when the attached pointcut matches.
 * 
 * @author andrew
 * @created Nov 17, 2010
 */
public class DSLContributionGroup extends ContributionGroup {
    private static final ParameterContribution[] NO_PARAMS = new ParameterContribution[0];

    private static final String NO_TYPE = "java.lang.Object";

    private static final String NO_NAME = "";

    /**
     * The closure that comes from the DSLD script.
     * It's delegate is set to <code>this</code>.
     */
    @SuppressWarnings("rawtypes")
    private final Closure contributionClosure;


    private VariableScope scope;
    
    // provider that is set for the entire contribution group
    // individual contributions can override
    private String provider = null;
    
    private ResolverCache resolver;
    
    private Map<String, Collection<Object>> bindings;

    private ClassNode currentType;
    
    private Map<String, Object> wormhole;

    public DSLContributionGroup(@SuppressWarnings("rawtypes") Closure contributionClosure) {
        this.contributionClosure = contributionClosure;
        
        if (contributionClosure != null) {
            contributionClosure.setDelegate(this);
            contributionClosure.setResolveStrategy(Closure.DELEGATE_FIRST);
        }
    }

    /**
     * This is the main entry point into the contribution
     */
    public List<IContributionElement> getContributions(GroovyDSLDContext pattern, BindingSet matches) {
        // uh oh...needs to be synchronized, or can we make this class stateless?
        synchronized (this) {
            List<IContributionElement> result;
            try {
                this.contributions = new ArrayList<IContributionElement>();
                this.scope = pattern.getCurrentScope();
                this.resolver = pattern.resolver;
                this.bindings = matches.getBindings();
                this.currentType = pattern.getCurrentType();
                this.wormhole = scope.getWormhole();
                contributionClosure.call();
            } catch (Exception e) {
                GroovyLogManager.manager.logException(TraceCategory.DSL, e);
            } finally {
                result = contributions;
                this.contributions = null;
                this.scope = null;
                this.resolver = null;
                this.bindings = null;
                this.currentType = null;
                this.wormhole = null;
            }
        return result;
        }
    }

    
    @Override
    public Object getProperty(String property) {
        if ("wormhole".equals(property)) {
            return wormhole;
        } else if ("currentNode".equals(property)) {
            return scope.getCurrentNode();
        } else if ("enclosingNode".equals(property)) {
            return scope.getEnclosingNode();
        } else if ("currentType".equals(property)) {
            return currentType;
        }
        return bindings.get(property);
    }
    
    /**
     * Called by closure to add a method
     * @param args
     */
    void method(Map<String, Object> args) {
        String name = asString(args.get("name"));
        
        Object value = args.get("type");
        String returnType = value == null ? "java.lang.Object" : asString(value);
        
        value = args.get("declaringType");
        String declaringType = value == null ? currentType.getName() : asString(value);
        
        value = args.get("provider");
        String provider = value == null ? this.provider : asString(value); // might be null
        value = args.get("doc");
        String doc = value == null ? null : asString(value); // might be null

        boolean useNamedArgs = asBoolean(args.get("useNamedArgs"));
        
        Map<Object, Object> paramsMap = (Map<Object, Object>) args.get("params");
        ParameterContribution[] params;
        if (paramsMap != null) {
            params = new ParameterContribution[paramsMap.size()];
            int i = 0;
            for (Entry<Object, Object> entry : paramsMap.entrySet()) {
                value = entry.getValue();
                String type = value == null ? "java.lang.Object" : asString(value);
                
                params[i++] = new ParameterContribution(asString(entry.getKey()), type);
            }
        } else {
            params = NO_PARAMS;
        }

        boolean isStatic = isStatic(args);

        if (!scope.isStatic() || (scope.isStatic() && isStatic)) {
            contributions.add(new MethodContributionElement(name == null ? NO_NAME : name, params, returnType == null ? NO_TYPE
                    : returnType, declaringType, isStatic, provider == null ? this.provider : provider, doc, useNamedArgs));
        }
    }

    /**
     * @param object
     * @return
     */
    private boolean asBoolean(Object object) {
        if (object == null) {
            return false;
        } 
        if (object instanceof Boolean) {
            return (Boolean) object;
        }
        String str = object.toString();
        return str.equalsIgnoreCase("true") ||
               str.equalsIgnoreCase("yes");
    }

    /**
     * Called by closure to add a property
     * @param args
     */
    void property(Map<String, Object> args) {
        String name = asString(args.get("name"));
        
        Object value = args.get("type");
        String type = value == null ? NO_TYPE : asString(value);
        
        value = args.get("declaringType");
        String declaringType = value == null ? currentType.getName() : asString(value);
        
        value = args.get("provider");
        String provider = value == null ? this.provider : asString(value); // might be null
        String doc = asString(args.get("doc")); // might be null
        boolean isStatic = isStatic(args);
        if (!scope.isStatic() || (scope.isStatic() && isStatic)) {
            contributions.add(new PropertyContributionElement(name == null ? NO_NAME : name, type,
                    declaringType, isStatic, provider, doc));
        }
    }

    /**
     * stub...will be used later to add templates
     * @param args
     */
    void template(Map<String, String> args) {
        
    }

    void delegatesTo(String className) {
        delegatesTo(this.resolver.resolve(className));
    }

    void delegatesTo(Class<?> clazz) {
        delegatesTo(this.resolver.resolve(clazz.getCanonicalName()));
    }
    
    /**
     * invoked by the closure
     * takes an expression and adds all members of its type to the augmented
     * class reference.
     */
    void delegatesTo(AnnotatedNode expr) {
        internalDelegatesTo(expr, false);
    }

    void delegatesToUseNamedArgs(String className) {
        delegatesToUseNamedArgs(this.resolver.resolve(className));
    }
    
    void delegatesToUseNamedArgs(Class<?> clazz) {
        delegatesToUseNamedArgs(this.resolver.resolve(clazz.getCanonicalName()));
    }
    
    /**
     * invoked by the closure
     * takes an expression and adds all members of its type to the augmented
     * class reference.
     */
    void delegatesToUseNamedArgs(AnnotatedNode expr) {
        internalDelegatesTo(expr, true);
    }
    
    /**
     * @param expr
     */
    private void internalDelegatesTo(AnnotatedNode expr, boolean useNamedArgs) {
        ClassNode type;
        if (expr instanceof ClassNode) {
            type = (ClassNode) expr;
        } else if (expr instanceof FieldNode) {
            type = ((FieldNode) expr).getType();
        } else if (expr instanceof MethodNode) {
            type = ((MethodNode) expr).getReturnType();
        } else if (expr instanceof ClassExpression) {
            type = ((ClassExpression) expr).getType();
        } else {
            // invalid
            if (GroovyLogManager.manager.hasLoggers()) {
                GroovyLogManager.manager.log(TraceCategory.DSL, 
                        "Cannot invoke delegatesTo() on an invalid object: " + expr);
            }
            return;
        }
        if (!type.getName().equals(Object.class.getName())) {
            for (MethodNode method : type.getMethods()) {
                if (!(method instanceof ConstructorNode)) {
                    contributions.add(new MethodContributionElement(method.getName(), toParameterContribution(method
                            .getParameters()), method.getReturnType().getName(), type.getName(), method.isStatic(), provider,
                            null, useNamedArgs));
                }
            }
        }
    }
    
    private ParameterContribution[] toParameterContribution(Parameter[] params) {
        if (params != null) {
            ParameterContribution[] contribs = new ParameterContribution[params.length];
            for (int i = 0; i < contribs.length; i++) {
                contribs[i] = new ParameterContribution(params[i]);
            }
            return contribs;
        } else {
            return new ParameterContribution[0];
        }
    }

    
    void provider(Object args) {
        provider = args == null ? null : asString(args);
    }
    
    /**
     * @param args map passed in from the call to method or property
     * @return true iff the static argument is passed in.
     */
    private boolean isStatic(Map<?, ?> args) {
        Object maybeStatic = args.get("isStatic");
        if (maybeStatic == null) {
            return false;
        } else if (maybeStatic instanceof Boolean) {
            return (Boolean) maybeStatic;
        } else {
            return Boolean.getBoolean(maybeStatic.toString());
        }
    }

    /**
     * Converts an object into a string
     * @param value
     * @return
     */
    private String asString(Object value) {
        if (value == null) {
//            return "null";
            return null;
        } else if (value instanceof String) {
            return (String) value;
        } else if (value instanceof ClassNode) {
            return ((ClassNode) value).getName();
        } else if (value instanceof FieldNode) {
            return ((FieldNode) value).getDeclaringClass().getName() + "." + ((FieldNode) value).getName();
        } else if (value instanceof MethodNode) {
            return ((MethodNode) value).getDeclaringClass().getName() + "." + ((MethodNode) value).getName();
        } else if (value instanceof AnnotationNode) {
            return ((AnnotationNode) value).getClassNode().getName();
        } else if (value instanceof Class) {
            return ((Class<?>) value).getName();
        } else {
            return value.toString();
        }
    }
}
