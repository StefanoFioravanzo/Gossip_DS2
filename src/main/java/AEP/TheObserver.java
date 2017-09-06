package AEP;

import AEP.messages.ObserverUpdate;
import AEP.messages.ObserverUpdateRate;
import AEP.messages.SetupMessage;
import AEP.nodeUtilities.Delta;
import akka.actor.UntypedActor;

import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Created by StefanoFiora on 30/08/2017.
 */
public class TheObserver extends UntypedActor {

    private int participantNumber;
    private int timesteps;
    private int finalTimestep;
    private String pathname;
    private int historyProcess;

    private ArrayList<ArrayList<ArrayList<Delta>>> history;

    // these array lists are #timesteps long
    private ArrayList<ArrayList<Integer>> maxStalePerProcess;
    private int[] maxStale;
    private int[] numStale;

    // keep track of the updateRate used by a chosen process at each time step
    private float[] updateRates;

    SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");

    private void initializeObserved(SetupMessage message) {
        this.participantNumber = message.getPs().size();
        this.finalTimestep = message.getTimesteps().get(message.getTimesteps().size()-1);
        this.timesteps = message.getTimesteps().get(message.getTimesteps().size()-1);
        this.pathname = message.getStoragePath();
        this.historyProcess = message.getChosenProcess();
        this.maxStalePerProcess = new ArrayList<>(timesteps);
        this.maxStale = new int[timesteps];
        this.numStale = new int[timesteps];
        this.updateRates = new float[timesteps];
        System.out.println("Observer chosen participant: " + this.historyProcess);

        this.history = new ArrayList<>();
        for (int i = 0; i < timesteps; i++) {
            ArrayList<ArrayList<Delta>> process = new ArrayList<>();
            maxStalePerProcess.add(new ArrayList<>());
            for (int j = 0; j < participantNumber; j++) {
                process.add(new ArrayList<>());
                maxStalePerProcess.get(i).add(0);
            }
            this.history.add(process);
        }
    }

    private void saveUpdateRate(ObserverUpdateRate message){
        // TODO: now we check here for adding just the chosen process. We should do it from the actors perspective.
        if (message.getId() == this.historyProcess){
            // for now we take a very simple approach: just save the incoming updateRate in the
            // given time step. Possible drawback: A single node changes its UR multiple times
            // in a single ts, so we may have to average this URs to get a precise measurement.
            this.updateRates[message.getTimestep()] = message.getUpdateRate();
        }
    }

    private synchronized void update(ObserverUpdate message) {
        Integer id = message.getId();
        ArrayList<Delta> updates = message.getUpdates();
        Integer ts = message.getTimestep();

        if (ts == this.finalTimestep) {
            saveAndKill();
        }


        System.out.println(sdf.format(new Date(System.currentTimeMillis())) + ": Storing " + updates + " from " + id);

        for(Delta d : updates) {
            if (!this.history.get(ts).get(id).contains(d))
                this.history.get(ts).get(id).add(d);
        }
    }

    private void saveAndKill(){

//        computeMaxStale();

        for (int i = 0; i < participantNumber; i++) {
            computeNumStale(i);
        }
        for (int i = 0; i < timesteps; i++) {
            maxStale[i] = Collections.max(maxStalePerProcess.get(i));
        }

        save();

        context().system().terminate();
    }

    // filter out and take only the local updates if locals == true, ore the non local updates if locals == false
    private ArrayList<Delta> getLocals (ArrayList<Delta> updates, int process, boolean locals) {
        ArrayList<Delta> localDeltas = new ArrayList<>();

        for (Delta d: updates) {
            if (d.getP() == process && locals) {
                localDeltas.add(d);
            } else if (d.getP() != process && !locals){
                localDeltas.add(d);
            }
        }

        return localDeltas;
    }

    private void computeNumStale(Integer mainProcess) {

        // a map that keeps track of the stale deltas present in the historyProcess
        // At each timestep the reconciled deltas are removed, the ones that remain increase the stale counter
        // At each timestep we take the element with higher counter.
        // first key is the process, second key is the delta key
        TreeMap<Integer, TreeMap<Integer, Integer>> maxStaleCounter = new TreeMap<>();
        for (int j = 0; j < participantNumber; j++) {
            maxStaleCounter.put(j, new TreeMap<>());
        }

        ArrayList<Delta> tmp = new ArrayList<>();

        // ts loop
        for (int i = 0; i < history.size(); i++) {
            for (int j = 0; j < history.get(i).size(); j++) {
                if (j != mainProcess){
                    tmp.addAll(getLocals(this.history.get(i).get(j), j, true));
                }
            }

            ArrayList<Delta> reconc = getLocals(this.history.get(i).get(mainProcess), mainProcess, false);
            ArrayList<Delta> inter = intersection(tmp, reconc);
            ArrayList<Delta> cose = intersection(tmp, reconc);

            assert inter.size() >= new HashSet<>(inter).size();

            int size = tmp.size();
            // we remove from all the local updates of timestep ts the
            // reconciled updates happened at historyProcess participant
            // In this way we leave inside tmp just the local updates that were
            // not propagated to historyProcess participant.

            cose.removeAll(tmp);
            if (cose.size() != 0)
                System.out.println("Ciao mamma!");

            boolean changed = tmp.removeAll(inter);  // returns true if the operation changes the list

            assert size == (tmp.size() + inter.size());

            if (numStale[i] < tmp.size()) // we take the maximum numStale among all the participant, for the same ts
                numStale[i] = tmp.size();
            // TODO: tieni solo numStale di un singolo processo, non il max fra tutti

            if(i == 64 && tmp.size() > 0){
                for (Delta y: tmp
                     ) {
                    System.out.println(mainProcess + " STRONZO " + y);
                }

            }

            // first we need to remove from the tree map the Deltas that have been reconciled
            if (changed) { // otherwise we don't even bother
                for (Delta d : inter){
                    if (maxStaleCounter.get(d.getP()).containsKey(d.getK())){
                        maxStaleCounter.get(d.getP()).remove(d.getK());
                    }
                }
            }

            /*
             tmp contains now the deltas that are stale in process historyProcess at timestep i
             so now we can do two things:
                - if the delta was stale at previous ts, then it is already present in the treemap and we increase the counter
                - if the delta was not stale before, then we insert the key in the treemap
            */
            for (Delta d : tmp) {
                if (maxStaleCounter.get(d.getP()).containsKey(d.getK())){  // increase staleness of this key
//                    maxStaleCounter.get(d.getP()).put(d.getK(), maxStaleCounter.get(d.getP()).get(d.getK()) + 1);
                } else {
                    maxStaleCounter.get(d.getP()).put(d.getK(), 0);
                }
            }
            for (Integer key : maxStaleCounter.keySet()){
                maxStaleCounter.get(key).replaceAll((k, v) -> v + 1);
            }

            // now get the maximum value in the treeMap and elect that key as maximum stale
            ArrayList<Integer> maxes = new ArrayList<>();
            for (int j = 0; j < participantNumber; j++) {
                if (maxStaleCounter.get(j).size() > 0){
                    maxes.add(Collections.max(maxStaleCounter.get(j).values()));
                }
            }
            if (maxes.size() > 0){
//                maxStale[i] = Collections.max(maxes);
                // at timestep i per process mainProcess
                maxStalePerProcess.get(i).set(mainProcess, Collections.max(maxes));
            }
        }
    }

    public ArrayList<Delta> intersection(ArrayList<Delta> list1, ArrayList<Delta> list2) {
        ArrayList<Delta> list = new ArrayList<>();
        ArrayList<Delta> toBeAdded;
        boolean newest;

        // should take only the ones which stop the staleness (i.e. are the newest)!
        for (Delta d2 : list2) {
            newest = true;
            toBeAdded = new ArrayList<>();

            for(Delta d1 : list1) {
                if (d1.getP() == d2.getP() && d1.getK() == d2.getK()) {
                    if(d1.getN() > d2.getN()) {
                        newest = false;
                        break;
                    } else {
                        toBeAdded.add(d1);
                    }
                }
            }
            if (newest)
                list.addAll(toBeAdded);
        }

        return list;
    }

    private String arrayString(){
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxStale.length; i++) {
            sb.append(maxStale[i]).append(" ");
        }
        sb.append("\n");
        for (int i = 0; i < numStale.length; i++) {
            sb.append(numStale[i]).append(" ");
        }
        sb.append("\n");
        for (int i = 0; i < updateRates.length; i++) {
            sb.append(updateRates[i]).append(" ");
        }
        return sb.toString();
    }

    private void save(){
        try {
            FileWriter out = new FileWriter(pathname);
            out.write(arrayString());
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onReceive(Object message) throws Exception {
//        logger.info("Received Message {}", message.toString());

        // class name is represented as dynamo.messages.className, so split and take last element.
        switch (message.getClass().getName().split("[.]")[2]) {
            case "SetupMessage": // initialization message
                initializeObserved((SetupMessage) message);
                break;
            case "ObserverUpdate": // initialization message
                update((ObserverUpdate) message);
                break;
            case "ObserverUpdateRate":
                saveUpdateRate((ObserverUpdateRate) message);
        }
    }
}
