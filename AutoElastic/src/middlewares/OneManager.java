/*
 * 25/04/2014
 * OneManager
 * - Classe destinada a fazer a a realizar operações e leituras no ambiente
 * 27/10/2014 - viniciusfacco
 *            - Upated to get allocatedMEM, usedMEM and allMonitoringTimes from the pool
 */

package middlewares;

import static autoelastic.AutoElastic.gera_log;
import communication.SSHClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JTextArea;
import javax.xml.parsers.ParserConfigurationException;
import org.opennebula.client.Client;
import org.opennebula.client.ClientConfigurationException;
import org.xml.sax.SAXException;

/**
 * Cloud Manager
 * @author viniciusfacco
 * 11/08/2015 - viniciusfacco
 *            - removed sleep between VM allocations
 *            - added method to set the SSHClient in the OneCommunicator
 */
public class OneManager {
    
    private static final String objname = "middlewares.OneManager"; //name of the class to be used in the logs
    private Client oneClient; //cliente de conexão com o servidor opennebula
    private OneHostPool ohpool; //conjunto de hosts que serão monitorados e utilizados
    private OneCommunicator messenger; //comunicador utilizado para realizar a comunicação de operações de elasticidade
    private final String user; //usuario para conexão com o OpenNebula
    private final String password; //senha para conexão com o OpenNebula
    private final String server_address; //IP do servidor OpenNebula
    private final int server_port = 2633; //porta de conexão com o servidor OpenNebula
    private final String[] iphosts;
    private static String image_manager;
    private static String virtual_machine_manager;
    private static String virtual_network_manager;
    private final int cluster_id;
    private final JTextArea log;
    private final int vms_for_host;
    private final int vmtemplateid;
    private boolean waiting_vms;
    private final ArrayList<OneVM> last_vms;
    
    public OneManager(  String puser, 
                        String ppassword, 
                        String pserver_address, 
                        String[] hosts, 
                        String pim, 
                        String pvmm, 
                        String pvnm, 
                        int pcid, 
                        JTextArea plog, 
                        int pvms_for_host, 
                        int pvmtemplateid){
        user = puser;
        password = ppassword;
        server_address = pserver_address;
        iphosts = hosts;
        image_manager = pim;
        virtual_machine_manager = pvmm;
        virtual_network_manager = pvnm;
        cluster_id = pcid;
        log = plog;
        vms_for_host = pvms_for_host;
        vmtemplateid = pvmtemplateid;
        waiting_vms = false;
        messenger = new OneCommunicator(pserver_address, puser, ppassword, plog);
        last_vms = new ArrayList<OneVM>();
        //[vms_for_host]; //array que vai receber as novas vms criadas
    }
    
    public boolean serverConnect(){
        try {
            //> realiza conexão com o front-end
           
            oneClient = new Client(user + ":" + password, "http://" + server_address + ":" + server_port + "/RPC2");
            
            if (oneClient.get_version().getMessage() != null){ //try to get the version. if null is because we do not have connection with the server
                gera_log(objname,"Versão do OpenNebula: " + oneClient.get_version().getMessage());
                //>criação dos hosts que podem ser utilizados
                ohpool = new OneHostPool(oneClient, iphosts, log, image_manager, virtual_machine_manager, virtual_network_manager, cluster_id);
                //>verifica e cria os hosts no gerenciador que já estão rodando no opennebula
                ohpool.atualiza_hosts(oneClient);
                return true;            
            } else {
                return false;
            }
        } catch (ClientConfigurationException ex) {
            Logger.getLogger(OneManager.class.getName()).log(Level.SEVERE, null, ex);
        } catch (SAXException ex) {
            Logger.getLogger(OneManager.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(OneManager.class.getName()).log(Level.SEVERE, null, ex);
        } catch (Exception ex) {
            Logger.getLogger(OneManager.class.getName()).log(Level.SEVERE, null, ex);
        }
        return false;
    }
    
    public void syncData(){
        ohpool.sync_hosts(); //sincroniza dados dos hosts
    }
    
    /**
     * Return the load of the cloud based on CPU
     * @return [0 &lt load &lt 1]
     */
    public float getCPULoad(){
        float used = ohpool.get_used_CPU();
        float allocated = ohpool.get_allocated_CPU();        
        float load = used / allocated;
        return load;
    }
    
    //return the total of CPU available in the cloud
    public float getAllocatedCPU(){
        return ohpool.get_allocated_CPU();
    }
    
    /**
     * Return the amount of busy CPU
     * @return 0 &lt CPU 
     */
    public float getUsedCPU(){
        return ohpool.get_used_CPU();
    }
    
    //return the total of MEM available in the cloud
    public float getAllocatedMEM(){
        return ohpool.get_allocated_MEM();
    }
    
    //return the current use of MEM
    public float getUsedMEM(){
        return ohpool.get_used_MEM();
    }
    
    /**
     * Return the list of the last monitor time of each host.
     * @return the times in string separeted by ","
     */
    public String getLastMonitorTimes(){
        return ohpool.get_last_monitor_times();
    }
    
    //return the current number of hosts in use
    public int getTotalActiveHosts(){
        return ohpool.get_total_ativos();
    }
    
    public int getTotalActiveVms()
    {
        return getTotalActiveHosts() * vms_for_host;
    }
    
    /**
     * Create a new host with virtual machines.
     * @return
     * @throws InterruptedException
     * @throws Exception
     */
    public boolean increaseResources(int quantity) throws InterruptedException, Exception{
        //int hostid = ohpool.allocatesHostNow(oneClient); //allocates the host and it will be active immediatly
        last_vms.clear();
        int pos = 0;
        
        // desconsidera a quantidade de vms rodando
        quantity -= getTotalActiveVms();
        
        if (quantity <= 0) { // para garantir que sempre realize uma operação de elasticidade
            quantity = vms_for_host;
        }
        while (quantity > 0) { 
            gera_log(objname, "Increase Resources...");
            int hostid = ohpool.allocatesHost(oneClient, pos); //allocates the host and it will be active after resorces be online
            pos++;
            gera_log(objname, "Host: " + hostid);
            if (hostid > 0){
                for (int i = 0; i < vms_for_host; i++) {
                    OneVM curVM = new OneVM(vmtemplateid);
                    last_vms.add(curVM);
                    increaseVM(curVM, hostid); //aloca vm nesse host
                    gera_log(objname,"Main: Nova VM alocada: " + curVM.get_id());
                    ohpool.get_onehost(hostid).add_vm(curVM);
                    System.out.println("Alocando vm...");
                    //Thread.sleep(10000); //why?
                    quantity--;
                }
                waiting_vms = true;
                return true;
            } else {
                break;
            }
        }
        return false;
    }
    
    //método que remove um host e suas máquinas virtuais no ambiente
    public boolean decreaseResources(int quantity) throws InterruptedException, IOException{
        // desconsidera a quantidade de vms rodando
        quantity -= getTotalActiveVms();
        
        if (quantity <= 0) { // para garantir que sempre realize uma operação de elasticidade
            quantity = vms_for_host;
        }
        boolean result = false;
        if (messenger.notifyDecrease()){
            result = true;
            while(!messenger.canDecrease()){}
            while (quantity > 0) {
                if (!ohpool.remove_host(oneClient)) { //remove último host criado e suas vms também
                    return false;
                }
                quantity -= vms_for_host;
            }
            
        }
        return result;
    }
    
    /**
     * Remove the host with the highest id and its virtual machines without ask permission
     * @return
     * @throws InterruptedException
     */
    public boolean decreaseResourcesHard() throws InterruptedException{
        return ohpool.remove_host(oneClient);//remove último host criado e suas vms também
    }
    
    //método que aloca máquina virtual em host específico
    private int increaseVM(OneVM ov, int hid) {
        return ov.deploy(oneClient, hid, log);
    }
    
    public boolean newResourcesPending() throws ParserConfigurationException, SAXException, IOException, InterruptedException{        
        if (waiting_vms){
            boolean canPing = true;
            boolean activeLCM = true;
            String message = "";
            
            for (int i=0; i<last_vms.size(); i++) {
                OneVM vm = last_vms.get(i);
                gera_log(objname, "IP: " + vm.get_ip());
                message += vm.get_ip() + "\n";
                if (vm.get_ip().equalsIgnoreCase("")) {
                    return false;
                } else {
                    if (!ping(vm.get_ip())){
                        canPing = false;
                    }
                    
                    if (vm.get_lcm_state() != 3) {
                        activeLCM = false;
                    }
                //System.out.println("STATE: " + last_vms[0].get_state());
                }
            }
            
            if (canPing || activeLCM) {
                ohpool.enableLastHost();
                waiting_vms = false;
                if (canPing) {
                    //gera_log(objname,"Notifica criação de novos recursos...");
                    messenger.notifyNewResources(message);
                }
            }
        }
        return waiting_vms;
    }
    
    //ping :)
    private boolean ping(String ip) {
        String resposta;
        int fim;
        boolean online = false;
        String comando = "ping -n 1 -w 600 " + ip;
        try {
            Scanner s = new Scanner(Runtime.getRuntime().exec("cmd /c " + comando).getInputStream());
            while (s.hasNextLine()) {
                resposta = s.nextLine() + "\n";
                fim = resposta.length() - 5;
                for (int j = ip.length() + 13; j <= fim; j++) {
                    if (resposta.substring(j, j + 5).equals("tempo")) {
                        online = true;
                        break;
                    }
                }
                if (online) {
                    break;
                }
            }
        } catch (Exception e) {
        }
        return online;
    }
    
    public void setSSHClient(SSHClient sshClient) {
        this.messenger.setSSHClient(sshClient);
    }
    
    public void reset(){
        this.waiting_vms = false;
    }
    
    //================================ Métodos não utilizados ========================================
    //================================ Implementação para futura nova versão =========================
    //instantiate "amounthosts" hosts with "vmsperhost" virtual machines each one / return an array with the IPs of the new virtual machines

    /**
     * Unused
     * @param amounthosts
     * @param vmsperhost
     * @param idtemplatevm
     * @return
     * @throws ParserConfigurationException
     * @throws SAXException
     * @throws IOException
     */
        public String[] instantiate_resources(int amounthosts, int vmsperhost, int idtemplatevm) throws ParserConfigurationException, SAXException, IOException{
        int hostid;
        int vmid;
        String[] ipvms = new String[vmsperhost * amounthosts]; //create a array to store the IPs of all new virtual machines
        int countvm = 0; //in the end the total of virtual machines
        for(int i = 0; i < amounthosts; i++){ //for each new host
            hostid = instantiate_host(); //create a new host
            if (hostid > 0){ //if success and a new host was create
                //gera_log(objname,"Main: Novo host alocado: " + hostid); //log
                //gera_log(objname,"Main: Aloca nova VM no host ID " + hostid); //log
                for (int j = 0; j < vmsperhost; j++){ //create vmsperhost new virtual machines
                    vmid = instantiate_vm(hostid, idtemplatevm); //create a new virtual machine in "hostid"
                    //gera_log(objname,"Main: Nova VM alocada: " + vmid); //log
                    ipvms[countvm] = ohpool.get_onehost(hostid).get_vm(0).get_ip(); //store the IP of this virtual machine
                    countvm++;
                }
            } else {//problem in host allocation
                gera_log(objname,"Main: Host não alocado..."); //log
            }
        }
        return ipvms;
    }
    
    //instantiate a new virtual machine (vmtemplateid) in the "hid" host
    private int instantiate_vm(int hid, int vmtemplateid) throws ParserConfigurationException, SAXException, IOException {
        int vmid;
        OneVM vm = new OneVM(vmtemplateid);
        vmid = vm.deploy(oneClient, hid, log);
        ohpool.get_onehost(hid).add_vm(vm); //add this virtual machine in the "ohpool"
        return vmid;
    }
    
    //instantiate a new host in the cloud
    private int instantiate_host(){
        int hostid = 0;
        try {
            hostid = ohpool.allocatesHostNow(oneClient); //create a new host in the "ohpool"
        } catch (Exception ex) {
            Logger.getLogger(OneManager.class.getName()).log(Level.SEVERE, null, ex);
        }
        return hostid;
    }
    
}
