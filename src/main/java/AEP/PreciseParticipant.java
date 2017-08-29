package AEP;

import AEP.messages.GossipMessage;
import AEP.messages.StartGossip;
import AEP.nodeUtilities.Delta;

import java.util.ArrayList;

/**
 * Created by StefanoFiora on 28/08/2017.
 */
public class PreciseParticipant extends Participant {

    // Maximum Transfer Unit: maximum number of deltas inside a single gossip message
    protected int mtu = 5;

    public static enum Ordering { OLDEST, NEWEST};
    protected Ordering method;

    public PreciseParticipant(String destinationPath, int id) {
        super(destinationPath, id);
        this.method = Ordering.OLDEST;
    }

    protected void startGossip(StartGossip message){
        logger.debug("First phase: Digest from " + getSender());

        // send to p the second message containing own digest
        getSender().tell(new GossipMessage(false, storage.createDigest()), self());

        logger.debug("Second phase: sending digest to " + getSender());
    }

    protected void gossipMessage(GossipMessage message){
        // p sent to q the updates
        if (message.isSender()) {
            storage.reconciliation(message.getParticipantStates());

            // answer with the updates p has to do. Sender set to null because we do not need to answer to this message
            ArrayList<Delta> toBeUpdated = storage.computeDifferences(message.getParticipantStates());
            getSender().tell(new GossipMessage(false, storage.mtuResizeAndSort(toBeUpdated, mtu ,this.method)), null);

            logger.debug("Fourth phase: sending differences to " + getSender());
        } else {
            // receiving message(s) from q.
            if (getSender() == null) { // this is the message with deltas
                storage.reconciliation(message.getParticipantStates());
                logger.debug("Gossip completed");
            } else { // digest message to respond to
                // send to q last message of exchange with deltas.
                ArrayList<Delta> toBeUpdated = storage.computeDifferences(message.getParticipantStates());
                getSender().tell(new GossipMessage(true, storage.mtuResizeAndSort(toBeUpdated, mtu, this.method)), self());
                logger.debug("Third phase: sending differences to " + getSender());
            }
        }
    }
}
