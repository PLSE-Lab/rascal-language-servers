/*
 * Copyright (c) 2018-2023, NWO-I CWI and Swat.engineering
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.rascalmpl.vscode.lsp.dap;

import io.usethesource.vallang.ISourceLocation;
import org.rascalmpl.debug.IRascalFrame;
import org.rascalmpl.interpreter.Evaluator;
import org.rascalmpl.interpreter.result.IRascalResult;
import org.rascalmpl.uri.URIUtil;
import org.rascalmpl.vscode.lsp.dap.variable.RascalVariable;
import org.rascalmpl.vscode.lsp.dap.variable.VariableSubElementsCounter;
import org.rascalmpl.vscode.lsp.dap.variable.VariableSubElementsCounterVisitor;
import org.rascalmpl.vscode.lsp.dap.variable.VariableSubfieldsVisitor;

import java.util.*;

/**
 * Class used to store the state of the Rascal Evaluator when it is suspended
 */
public class SuspendedState {

    private final Evaluator evaluator;
    private volatile IRascalFrame[] currentStackFrames;
    private final Map<Integer, RascalVariable> variables;
    private final Map<Integer, IRascalFrame> scopes;
    private volatile int referenceIDCounter;

    private volatile boolean isSuspended;


    public SuspendedState(Evaluator evaluator){
        this.evaluator = evaluator;
        this.variables = new HashMap<>();
        this.scopes = new HashMap<>();
    }

    public void suspended(){
        Stack<IRascalFrame> stack = evaluator.getCurrentStack();
        currentStackFrames = stack.toArray(IRascalFrame[]::new);
        referenceIDCounter = 0;
        this.variables.clear();
        this.scopes.clear();
        this.isSuspended = true;
    }

    public ISourceLocation getCurrentLocation(){
        return evaluator.getCurrentPointOfExecution() != null ?
            evaluator.getCurrentPointOfExecution()
            : URIUtil.rootLocation("stdin");
    }

    public void resumed(){
        this.isSuspended = false;
    }

    public boolean isSuspended() {
        return isSuspended;
    }

    public IRascalFrame[] getCurrentStackFrames(){
        return currentStackFrames;
    }

    public IRascalFrame getCurrentStackFrame(){
        return currentStackFrames[currentStackFrames.length - 1];
    }

    public synchronized int addScope(IRascalFrame frame){
        scopes.put(++referenceIDCounter, frame);
        return referenceIDCounter;
    }

    public List<RascalVariable> getVariables(int referenceID, int startIndex, int maxCount){
        List<RascalVariable> variableList = new ArrayList<>();

        if(referenceID < 0){
            return variableList;
        }

        // referenceID is a stack frame reference id
        if(scopes.containsKey(referenceID)){
            IRascalFrame frame = scopes.get(referenceID);
            List<String> frameVariables = new ArrayList<>(frame.getFrameVariables());
            frameVariables.sort(String::compareTo);
            int endIndex = maxCount == -1 ? frameVariables.size() : Math.min(frameVariables.size(), startIndex + maxCount);
            for (String varname : frameVariables.subList(startIndex, endIndex)) {
                IRascalResult result = frame.getFrameVariable(varname);
                RascalVariable refResult = new RascalVariable(result.getStaticType(), varname, result.getValue());
                if(refResult.hasSubFields()){
                    addVariable(refResult);
                    VariableSubElementsCounter counter = result.getValue().accept(new VariableSubElementsCounterVisitor());
                    refResult.setIndexedVariables(counter.getIndexedVariables());
                    refResult.setNamedVariables(counter.getNamedVariables());
                }
                variableList.add(refResult);
            }
            return variableList;
        }

        if(!variables.containsKey(referenceID)) return variableList;

        // referenceID is a variable ID
        RascalVariable var = variables.get(referenceID);
        return var.getValue().accept(new VariableSubfieldsVisitor(this, var.getType(), startIndex, maxCount));
    }

    public synchronized void addVariable(RascalVariable variable){
        variable.setReferenceID(++referenceIDCounter);
        variables.put(referenceIDCounter, variable);
    }

}
