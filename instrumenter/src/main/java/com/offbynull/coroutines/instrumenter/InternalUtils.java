/*
 * Copyright (c) 2017, Kasra Faghihi, All rights reserved.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 */
package com.offbynull.coroutines.instrumenter;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static org.apache.commons.codec.digest.DigestUtils.md5;
import org.apache.commons.lang3.Validate;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;

final class InternalUtils {
    private InternalUtils() {
        // do nothing
    }
    
    @SuppressWarnings("unchecked")
    static <T extends ContinuationPoint> T validateAndGetContinuationPoint(
            MethodAttributes attrs, int idx, Class<T> expectedType) {
        Validate.notNull(attrs);
        Validate.notNull(expectedType);
        Validate.isTrue(idx >= 0);
        
        List<ContinuationPoint> continuationPoints = attrs.getContinuationPoints();
        Validate.isTrue(idx < continuationPoints.size());
        
        ContinuationPoint continuationPoint = continuationPoints.get(idx);
        Validate.isTrue(expectedType.isAssignableFrom(continuationPoint.getClass()));
        
        return (T) continuationPoint;
    }

    static int generateMethodId(String className, String methodName, String methodDesc) {
        Validate.notNull(className);
        Validate.notNull(methodName);
        Validate.notNull(methodDesc);
        
        // Note we don't include return type as part of the ID because we want to be able to change the return type but still point to the
        // same method. Remember that you can't overload methods by return type.

        byte[] methodIdHash = md5(className
                + methodName
                + methodDesc);

        return ByteBuffer.wrap(methodIdHash).getInt();
    }

    // Takes into account the instructions and operands, as well as the overall structure.
    static int generateMethodVersion(MethodNode methodNode) {
        // Calculate label offsets -- required for hash calculation
          // we only care about where the labels are in relation to the opcode instructions -- we don't care about things like
          // LocalVariableNode or other ancillary data because these can change without the actual logic changing
        List<AbstractInsnNode> onlyInstructionsAndLabels = Arrays.stream(methodNode.instructions.toArray())
                .filter(x -> x instanceof LabelNode || x.getOpcode() != -1)
                .collect(Collectors.toList());
        Map<Label, Integer> labelOffsets = onlyInstructionsAndLabels.stream()
                .filter(x -> x instanceof LabelNode)
                .map(x -> (LabelNode) x)
                .collect(Collectors.toMap(x -> x.getLabel(), x -> onlyInstructionsAndLabels.indexOf(x)));


        // Hash based on overall structures and instructions+operands
        byte[] rawDataToHash;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream daos = new DataOutputStream(baos);) {
            MethodVisitor daosDumpMethodVisitor = new DumpToDaosMethodVisitor(daos, labelOffsets);

            methodNode.accept(daosDumpMethodVisitor);
            daos.flush(); // doesn't really need it -- just incase
            
            rawDataToHash = baos.toByteArray();
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe); // should never happen
        }

        byte[] methodIdHash = md5(rawDataToHash);


        // Return truncated hash
        return ByteBuffer.wrap(methodIdHash).getInt();
    }


    private static final class DumpToDaosMethodVisitor extends MethodVisitor {
        
        private final DataOutputStream daos;
        private final Map<Label, Integer> labelOffsets;

        DumpToDaosMethodVisitor(DataOutputStream daos, Map<Label, Integer> labelOffsets) {
            super(Opcodes.ASM5);

            this.daos = daos;
            this.labelOffsets = labelOffsets;
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            try {
                daos.writeChars("tcb");
                daos.writeInt(labelOffsets.get(start));
                daos.writeInt(labelOffsets.get(end));
                writePossiblyNullString(type);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void visitMultiANewArrayInsn(String desc, int dims) {
            try {
                daos.writeChars("mana");
                writePossiblyNullString(desc);
                daos.writeInt(dims);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            try {
                daos.writeChars("ls");
                daos.writeInt(labelOffsets.get(dflt));
                daos.writeInt(labels.length);
                for (Label label : labels) {
                    daos.writeInt(labelOffsets.get(label));
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            try {
                daos.writeChars("ts");
                daos.writeInt(min);
                daos.writeInt(max);
                daos.writeInt(labelOffsets.get(dflt));
                daos.writeInt(labels.length);
                for (Label label : labels) {
                    daos.writeInt(label.getOffset());
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            try {
                daos.writeChars("ii");
                daos.writeInt(var);
                daos.writeInt(increment);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void visitLdcInsn(Object cst) {
            try {
                daos.writeChars("l");
                writePossiblyNullString(cst.getClass().getName());
                writePossiblyNullString(cst.toString());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            try {
                daos.writeChars("j");
                daos.writeInt(opcode);
                daos.writeInt(labelOffsets.get(label));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
            try {
                daos.writeChars("id");
                writePossiblyNullString(name);
                writePossiblyNullString(desc);
                if (bsm != null) {
                    writePossiblyNullString(bsm.getName());
                    writePossiblyNullString(bsm.getDesc());
                    writePossiblyNullString(bsm.getOwner());
                    daos.writeInt(bsm.getTag());
                    daos.writeBoolean(bsm.isInterface());
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            try {
                daos.writeChars("m");
                daos.writeInt(opcode);
                writePossiblyNullString(owner);
                writePossiblyNullString(name);
                writePossiblyNullString(desc);
                daos.writeBoolean(itf);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            try {
                daos.writeChars("t");
                daos.writeInt(opcode);
                writePossiblyNullString(type);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            try {
                daos.writeChars("v");
                daos.writeInt(opcode);
                daos.writeInt(var);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            try {
                daos.writeChars("i");
                daos.writeInt(opcode);
                daos.writeInt(operand);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void visitInsn(int opcode) {
            try {
                daos.writeChars("i");
                daos.writeInt(opcode);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            try {
                daos.writeChars("d");
                daos.writeInt(opcode);
                writePossiblyNullString(owner);
                writePossiblyNullString(name);
                writePossiblyNullString(desc);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void visitLabel(Label label) {
            try {
                daos.writeChars("lb");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        
        private void writePossiblyNullString(String str) throws IOException {
            if (str == null) {
                daos.write(0);
            } else {
                daos.write(1);
                daos.writeUTF(str);
            }
        }
    }
}
