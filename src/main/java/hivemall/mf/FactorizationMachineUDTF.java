/*
 * Hivemall: Hive scalable Machine Learning Library
 *
 * Copyright (C) 2015 Makoto YUI
 * Copyright (C) 2013-2015 National Institute of Advanced Industrial Science and Technology (AIST)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package hivemall.mf;

import hivemall.UDTFWithOptions;
import hivemall.io.FMArrayModel;
import hivemall.io.FMMapModel;
import hivemall.io.FactorizationMachineModel;
import hivemall.utils.hadoop.HiveUtils;
import hivemall.utils.lang.Primitives;

import java.util.ArrayList;
import java.util.Arrays;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.serde2.objectinspector.ListObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorUtils;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.IntWritable;

public class FactorizationMachineUDTF extends UDTFWithOptions {

    private ListObjectInspector xOI;
    private PrimitiveObjectInspector yOI;
       
    private int[] x_group;

    // Learning hyper-parameters/options    
    protected boolean classification;
    protected float eta;
    protected long seed;
    protected int iterations;
    /**
     * The size of x
     */
    protected int p;

    protected FactorizationMachineModel model;

    /**
     * The number of training examples processed
     */
    protected int t;

    @Override
    protected Options getOptions() {
        Options opts = new Options();
        opts.addOption("C", "classification", false, "Act as classification");
        opts.addOption("eta", "eta0", true, "Initial learning rate [default: 0.1]");
        opts.addOption("seed", true, "Seed value [default: -1 (random)]");
        opts.addOption("iters", "iterations", true, "The number of iterations");
        opts.addOption("p", "size_x", true, "The size of x");
        return opts;
    }

    @Override
    protected CommandLine processOptions(ObjectInspector[] argOIs) throws UDFArgumentException {
        boolean classication = false;
        long seed = -1L;
        int iters = 1;
        float eta = 0.1f;
        int p = -1;

        CommandLine cl = null;
        if(argOIs.length >= 3) {
            String rawArgs = HiveUtils.getConstString(argOIs[2]);
            cl = parseOptions(rawArgs);
            classication = cl.hasOption("classification");
            seed = Primitives.parseLong(cl.getOptionValue("seed"), seed);
            iters = Primitives.parseInt(cl.getOptionValue("iters"), iters);
            eta = Primitives.parseFloat(cl.getOptionValue("eta"), eta);
            p = Primitives.parseInt(cl.getOptionValue("p"), p);
        }

        this.classification = classication;
        this.seed = seed;
        this.iterations = iters;
        this.eta = eta;
        this.p = p;

        return cl;
    }

    @Override
    public StructObjectInspector initialize(ObjectInspector[] argOIs) throws UDFArgumentException {
        if(argOIs.length != 3 && argOIs.length != 4) {
            throw new UDFArgumentException(getClass().getSimpleName()
                    + " takes 3 or 4 arguments: array<string> x, double y, constant int[] x_group, [, CONSTANT STRING options]: "
                    + Arrays.toString(argOIs));
        }

        this.xOI = HiveUtils.asListOI(argOIs[0]);
        if(!HiveUtils.isStringOI(xOI.getListElementObjectInspector())) {
            throw new UDFArgumentException("Unexpected Object inspector for array<string>: "
                    + argOIs[0]);
        }
        this.yOI = HiveUtils.asDoubleCompatibleOI(argOIs[1]);
        
        if(p == -1) {
            this.model = new FMMapModel();
        } else {
            this.model = new FMArrayModel(p); // TODO fix constructor
        }       
        this.t = 0;

        ArrayList<String> fieldNames = new ArrayList<String>();
        ArrayList<ObjectInspector> fieldOIs = new ArrayList<ObjectInspector>();
        fieldNames.add("idx");
        fieldOIs.add(PrimitiveObjectInspectorFactory.writableIntObjectInspector);
        fieldNames.add("W_i");
        fieldOIs.add(PrimitiveObjectInspectorFactory.writableFloatObjectInspector);
        fieldNames.add("V_if");
        fieldOIs.add(ObjectInspectorFactory.getStandardListObjectInspector(PrimitiveObjectInspectorFactory.writableFloatObjectInspector));

        return ObjectInspectorFactory.getStandardStructObjectInspector(fieldNames, fieldOIs);
    }

    @Override
    public void process(Object[] args) throws HiveException {
        Feature[] x = parseFeatures(args[0], xOI);
        double y = PrimitiveObjectInspectorUtils.getDouble(args[1], yOI);

        if(x == null) {
            return;
        }

        train(x, y, x_group);
        t++;
    }

    protected void train(@Nonnull final Feature[] x, final double y, final int[] group) {

    }

    @Override
    public void close() throws HiveException {
        int P = 0; // FIXME
        int size = P + 1;

        final Object[] forwardObjs = new Object[3];

        final IntWritable idx = new IntWritable(0);
        final FloatWritable Wi = new FloatWritable(0.f);
        final FloatWritable[] Vif = HiveUtils.newFloatArray(size, 0.f);

        forwardObjs[0] = idx;
        forwardObjs[1] = Wi;
        forwardObjs[2] = Arrays.asList(Vif);

        // FIXME
        for(int i = 0; i < P; i++) {
            idx.set(i+1);
            // set Wi
            // set Vif            
            forward(forwardObjs);
        }
    }

    @Nullable
    private static Feature[] parseFeatures(@Nonnull final Object arg, @Nonnull final ListObjectInspector listOI)
            throws HiveException {
        if(arg == null) {
            return null;
        }
        final int length = listOI.getListLength(arg);
        final Feature[] ary = new Feature[length];
        for(int i = 0; i < length; i++) {
            Object o = listOI.getListElement(arg, i);
            if(o == null) {
                continue;
            }
            String s = o.toString();
            Feature f = Feature.parse(s);
            ary[i] = f;
        }
        return ary;
    }

    protected static final class Feature {
        int index;
        double value;

        Feature() {}

        Feature(int index, double value) {
            this.index = index;
            this.value = value;
        }

        static Feature parse(@Nonnull final String s) throws HiveException {
            int pos = s.indexOf(":");
            String s1 = s.substring(0, pos);
            String s2 = s.substring(pos + 1);
            int index = Integer.parseInt(s1);
            if(index <= 0) {
                throw new HiveException("Feature index MUST be greater than 0: " + s);
            }
            double value = Double.parseDouble(s2);
            return new Feature(index, value);
        }

        static void parse(@Nonnull final String s, @Nonnull final Feature probe)
                throws HiveException {
            int pos = s.indexOf(":");
            String s1 = s.substring(0, pos);
            String s2 = s.substring(pos + 1);
            int index = Integer.parseInt(s1);
            if(index <= 0) {
                throw new HiveException("Feature index MUST be greater than 0: " + s);
            }
            double value = Double.parseDouble(s2);
            probe.index = index;
            probe.value = value;
        }
    }

}