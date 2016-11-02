/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package grainevaluators;

import static autoelastic.AutoElastic.gera_log;
import middlewares.OneManager;
import org.rosuda.JRI.REXP;
import org.rosuda.JRI.Rengine;
import thresholds.Thresholds;

/**
 *
 * @author luiz
 */
public class GrainEvaluator {
    
    private static final String objname = "grainevaluators.GrainEvaluator";
    private int cont;
    private final double loads[];
    private final int vms[];
    private static final int LOAD_SIZE=10;
    
    static Rengine re;
    static REXP resp;
    static int forecast = 8;
    
    private final OneManager cloudManager;
    private final Thresholds thresholds;
    
    
    public GrainEvaluator(OneManager cm, Thresholds t)
    {
        loads = new double[LOAD_SIZE];
        vms = new int[LOAD_SIZE];
        cont = 0;
        cloudManager = cm;
        thresholds = t;
        
        String args[] = {"--no-save"};
        re = new Rengine(args, true, null);
    }
    
    public void cycle() {
        cont++;
        saveLoad(cloudManager.getCPULoad(), cloudManager.getTotalActiveVms());
    }
    
    
    
    public int getNumberOfVms()
    {
        if (cont > loads.length) {
            double futureLoad = getFutureLoad();
            double vmCapacity = getVMCapacity();
            double averageLoad = getAverageLoad();
            
            float expectedLoad = getExpectedLoad();
            
            gera_log(objname, "Future Load: " + futureLoad);
            gera_log(objname, "VM Capacity: " + vmCapacity);
            gera_log(objname, "Average Load: " + averageLoad);
            gera_log(objname, "Expected Load: " + expectedLoad);
            int totalVMs = (int)Math.round(futureLoad / vmCapacity * averageLoad / expectedLoad);
            gera_log(objname, "Total of VMs: " + totalVMs);
            return totalVMs;
            //System.exit(-1);
            
            //return 1;
            
            
        }
        //gera_log(objname, "Morrendo...");
        //System.exit(-1);
        return 1;
    }
    
    private double getVMCapacity()
    {
        double average = getAverageLoad();

        return average / cloudManager.getTotalActiveVms();
    }
    
    private double getAverageLoad()
    {
        double sum = 0;
        for (int i=0; i< loads.length; i++) {
            sum += loads[i];
        }
        return sum / loads.length;
    }
    
    private double getFutureLoad()
    {
        int curMultiplier = 1;
        int lastMultiplier = 0;
        int sum = 0;
        float result = 0;
        
        re.assign("y", loads);
        re.eval("fit=arima(y, c(0,2,1))");
        resp = re.eval("f <- predict(fit, " + forecast + ")");
        float decision_load = (float) resp.asList().at(0).asDoubleArray()[forecast - 1];
        if(decision_load < 0)
            decision_load = 0;
        return decision_load;
    }
    
    private float getExpectedLoad()
    {
        return (thresholds.getUpperThreshold() + thresholds.getLowerThreshold()) / 2;
    }
    
    private void saveLoad(float load, int num_vms)
    {
        int pos = cont%loads.length;
        for (int i=0; i<pos; i++) {
            loads[i] = loads[i+1];
            vms[i] = vms[i+1];
        }
        
        loads[pos] = load;
        vms[pos] = num_vms;
    }
}
