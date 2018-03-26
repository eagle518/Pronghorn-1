package com.ociweb.pronghorn.network.http;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ociweb.json.JSONExtractor;
import com.ociweb.json.JSONExtractorCompleted;
import com.ociweb.pronghorn.network.config.HTTPHeader;
import com.ociweb.pronghorn.pipe.util.hash.IntHashTable;
import com.ociweb.pronghorn.struct.BStructSchema;
import com.ociweb.pronghorn.struct.BStructTypes;
import com.ociweb.pronghorn.util.Appendables;
import com.ociweb.pronghorn.util.TrieParser;
import com.ociweb.pronghorn.util.TrieParserReader;
import com.ociweb.pronghorn.util.TrieParserVisitor;

public class CompositeRouteImpl implements CompositeRoute {

	private static final Logger logger = LoggerFactory.getLogger(CompositeRouteImpl.class);
	
	//TODO: move this entire logic into HTTP1xRouterStageConfig to eliminate this object construction.
	private final JSONExtractorCompleted extractor; 
	private final URLTemplateParser parser; 
	private final IntHashTable headerTable;
	private final int routeId;
	private final AtomicInteger pathCounter;
	private final HTTP1xRouterStageConfig<?,?,?,?> config;
	private final ArrayList<FieldExtractionDefinitions> defs;
	private final TrieParserReader reader = new TrieParserReader(4,true);
	private final int structId;
    private final BStructSchema schema;	

	
    private int[] activePathFieldIndexPosLookup;
    
    private TrieParserVisitor modifyStructVisitor = new TrieParserVisitor() {
		@Override
		public void visit(byte[] pattern, int length, long value) {
			int inURLOrder = (int)value&0xFFFF;
			
			BStructTypes type = null;
			switch((int)(value>>16)) {
				case TrieParser.ESCAPE_CMD_SIGNED_INT:
					type = BStructTypes.Long;
					break;			
				case TrieParser.ESCAPE_CMD_RATIONAL:
					type = BStructTypes.Rational;
					break;
				case TrieParser.ESCAPE_CMD_DECIMAL:
					type = BStructTypes.Decimal;
					break;
				case TrieParser.ESCAPE_CMD_BYTES:
					type = BStructTypes.Blob;
					break;
				default:
					throw new UnsupportedOperationException("unknown value of "+(value>>16)+" for key "+new String(Arrays.copyOfRange(pattern, 0, length)));
			}
						
			long fieldId = schema.modifyStruct(structId, pattern, 0, length, type, 0);
		
			//must build a list of fieldId ref in the order that these are disovered
			//at postion inURL must store fieldId for use later... where is this held?
			//one per path.
			activePathFieldIndexPosLookup[inURLOrder-1] = (int)fieldId & BStructSchema.FIELD_MASK;
			
		}
    };
    
	public CompositeRouteImpl(BStructSchema schema,
			                  HTTP1xRouterStageConfig<?,?,?,?> config,
			                  JSONExtractorCompleted extractor, 
			                  URLTemplateParser parser, 
			                  IntHashTable headerTable,
			                  HTTPHeader[] headers,
			                  int routeId,
			                  AtomicInteger pathCounter) {
		
		this.defs = new ArrayList<FieldExtractionDefinitions>();
		this.config = config;
		this.extractor = extractor;
		this.parser = parser;
		this.headerTable = headerTable;
		this.routeId = routeId;
		this.pathCounter = pathCounter;
		this.schema = schema;
	    
		
		//begin building the structure with the JSON fields
		if (null==extractor) {
			//create structure with a single payload field
			
			byte[][] fieldNames = new byte[][]{"payload".getBytes()};
			BStructTypes[] fieldTypes = new BStructTypes[]{BStructTypes.Text};//TODO: should be array of bytes..
			int[] fieldDims = new int[]{0};
			this.structId = schema.addStruct(fieldNames, fieldTypes, fieldDims);
		} else {
			this.structId = ((JSONExtractor)extractor).toStruct(schema);
		}
		
		/////////////////////////
		//add the headers to the struct
	    //always add parser in order to ignore headers if non are requested.
		TrieParser headerParser = new TrieParser(256,2,false,true,false);
		
		headerParser.setUTF8Value("\r\n", config.END_OF_HEADER_ID);
		headerParser.setUTF8Value("\n", config.END_OF_HEADER_ID);
	
		if (null!=headers) {			
			int h = headers.length;
			while (--h>=0) {
				HTTPHeader header = headers[h];
				long fieldId = schema.growStruct(this.structId,
						BStructTypes.Blob,						
						//TODO: need a way to define dimensions on headers
						0,
						//NOTE: associated object will be used to interpret 
						header.rootBytes());
				
				schema.setAssociatedObject(fieldId, header);
								
				headerParser.setUTF8Value(header.readingTemplate(), "\r\n", fieldId);
				headerParser.setUTF8Value(header.readingTemplate(), "\n", fieldId);
			
			}
		
		}
		headerParser.setUTF8Value("%b: %b\r\n", config.UNKNOWN_HEADER_ID);        
		headerParser.setUTF8Value("%b: %b\n", config.UNKNOWN_HEADER_ID); //\n must be last because we prefer to have it pick \r\n
		config.storeRouteHeaders(routeId, headerParser);
		
		
	}

	@Override
	public int routeId(boolean debug) {
		
		if (debug) {
			parser.debugRouterMap("debugRoute");
			
			int i = defs.size();
			while (--i>=0) {
				try {
					defs.get(i).getRuntimeParser().toDOTFile(File.createTempFile("defs"+i,".dot"));
				} catch (IOException e) {
					throw new RuntimeException(e);
				}			
			}
			
		}
		
		return routeId;
	}

	@Override
	public int routeId() {
		return routeId;
	}
	
	@Override
	public CompositeRoute path(CharSequence path) {
		
		int pathsId = pathCounter.getAndIncrement();
		
		//logger.trace("pathId: {} assinged for path: {}",pathsId, path);
		FieldExtractionDefinitions fieldExDef = parser.addPath(path, routeId, pathsId);//hold for defaults..
				
		activePathFieldIndexPosLookup = new int[fieldExDef.getIndexCount()];		
		fieldExDef.getRuntimeParser().visitPatterns(modifyStructVisitor);
		fieldExDef.setPathFieldLookup(activePathFieldIndexPosLookup);
		
		config.storeRequestExtractionParsers(pathsId, fieldExDef); //this looked up by pathId
		config.storeRequestedJSONMapping(pathsId, extractor);
		config.storeRequestedHeaders(pathsId, headerTable);		
		defs.add(fieldExDef);
		
		
		return this;
	}
	
	@Override
	public CompositeRouteFinish defaultInteger(String key, long value) {
		byte[] keyBytes = key.getBytes();
		schema.modifyStruct(structId, keyBytes, 0, keyBytes.length, BStructTypes.Long, 0);
		
		int i = defs.size();
		while (--i>=0) {
			defs.get(i).defaultInteger(reader, keyBytes, value);			
		}
		return this;
	}

	@Override
	public CompositeRouteFinish defaultText(String key, String value) {
		byte[] keyBytes = key.getBytes();
		schema.modifyStruct(structId, keyBytes, 0, keyBytes.length, BStructTypes.Text, 0);
		
		int i = defs.size();
		while (--i>=0) {
			defs.get(i).defaultText(reader, keyBytes, value);			
		}
		return this;
	}

	@Override
	public CompositeRouteFinish defaultDecimal(String key, long m, byte e) {
		byte[] keyBytes = key.getBytes();
		schema.modifyStruct(structId, keyBytes, 0, keyBytes.length, BStructTypes.Decimal, 0);
		
		int i = defs.size();
		while (--i>=0) {
			defs.get(i).defaultDecimal(reader, keyBytes, m, e);			
		}
		return this;
	}
	
	@Override
	public CompositeRouteFinish defaultRational(String key, long numerator, long denominator) {
		byte[] keyBytes = key.getBytes();
		schema.modifyStruct(structId, keyBytes, 0, keyBytes.length, BStructTypes.Rational, 0);
		
		int i = defs.size();
		while (--i>=0) {
			defs.get(i).defaultRational(reader, keyBytes, numerator, denominator);			
		}
		return this;
	}


}
