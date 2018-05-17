package com.ociweb.pronghorn.network.module;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ociweb.pronghorn.network.ServerCoordinator;
import com.ociweb.pronghorn.network.config.HTTPContentType;
import com.ociweb.pronghorn.network.config.HTTPContentTypeDefaults;
import com.ociweb.pronghorn.network.config.HTTPHeader;
import com.ociweb.pronghorn.network.config.HTTPRevision;
import com.ociweb.pronghorn.network.config.HTTPSpecification;
import com.ociweb.pronghorn.network.config.HTTPVerb;
import com.ociweb.pronghorn.network.config.HTTPVerbDefaults;
import com.ociweb.pronghorn.network.schema.HTTPRequestSchema;
import com.ociweb.pronghorn.network.schema.ServerResponseSchema;
import com.ociweb.pronghorn.pipe.ChannelReader;
import com.ociweb.pronghorn.pipe.DataInputBlobReader;
import com.ociweb.pronghorn.pipe.Pipe;
import com.ociweb.pronghorn.stage.monitor.PipeMonitorCollectorStage;
import com.ociweb.pronghorn.stage.scheduling.GraphManager;
import com.ociweb.pronghorn.util.AppendableBuilder;

public class SummaryModuleStage<   T extends Enum<T> & HTTPContentType,
								R extends Enum<R> & HTTPRevision,
								V extends Enum<V> & HTTPVerb,
								H extends Enum<H> & HTTPHeader> extends AbstractAppendablePayloadResponseStage<T,R,V,H> {

	Logger logger = LoggerFactory.getLogger(DotModuleStage.class);
	
    public static SummaryModuleStage<?, ?, ?, ?> newInstance(GraphManager graphManager, PipeMonitorCollectorStage monitor, 
    		                      Pipe<HTTPRequestSchema>[] inputs, Pipe<ServerResponseSchema>[] outputs, HTTPSpecification<?, ?, ?, ?> httpSpec) {
    	return new SummaryModuleStage(graphManager, inputs, outputs, httpSpec, monitor);
    }
    
    public static SummaryModuleStage<?, ?, ?, ?> newInstance(GraphManager graphManager, Pipe<HTTPRequestSchema> input, Pipe<ServerResponseSchema> output, HTTPSpecification<?, ?, ?, ?> httpSpec) {
    	PipeMonitorCollectorStage monitor = PipeMonitorCollectorStage.attach(graphManager);		
        return new SummaryModuleStage(graphManager, new Pipe[]{input}, new Pipe[]{output}, httpSpec, monitor);
    }
	
    private final PipeMonitorCollectorStage monitor;
    
	private SummaryModuleStage(GraphManager graphManager, 
			Pipe<HTTPRequestSchema>[] inputs, 
			Pipe<ServerResponseSchema>[] outputs, 
			HTTPSpecification httpSpec, PipeMonitorCollectorStage monitor) {
		super(graphManager, inputs, outputs, httpSpec);
		this.monitor = monitor;
		
		if (inputs.length>1) {
			GraphManager.addNota(graphManager, GraphManager.LOAD_MERGE, GraphManager.LOAD_MERGE, this);
		}
        GraphManager.addNota(graphManager, GraphManager.DOT_BACKGROUND, "lemonchiffon3", this);
	}
	
	@Override
	protected byte[] payload(AppendableBuilder payload, 
			                 GraphManager gm, 
			                 ChannelReader params,
			                 HTTPVerbDefaults verb) {
	
		//logger.info("begin building requested graph");
		//NOTE: this class is exactly the same as DotModuleStage except for this line.
		monitor.writeAsSummary(gm, payload);
		
		//logger.info("finished requested dot");
		return null; //never cache this so we return null.
	}
	
	@Override
	protected byte[] contentType() {
		return HTTPContentTypeDefaults.DOT.getBytes();
	}

}
