package fr.pilato.elasticsearch.crawler.fs.tika.customparser;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.openjson.JSONArray;
import com.github.openjson.JSONObject;

import fr.pilato.elasticsearch.crawler.fs.tika.TikaDocParser;

public class CustomOcrUtil {
	
	private final static Logger logger = LogManager.getLogger(TikaDocParser.class);
	
	/**
	 * To extract original content from the extracted json with metadata.
	 * @param finalResponse from custom OCR with metadata to be passed here to get actual content
	 * @return StringBuilder with original extracted content from the document
	 */
	public static StringBuilder extractOriginalContentFromFinalJsonResponse(String finalResponse) {

		StringBuilder builder = new StringBuilder();
		JSONObject jsonResponse = new JSONObject(finalResponse);
		JSONArray pages = (JSONArray) jsonResponse.get("recognitionResults");
		int r = 0;
		for (int i = 0; i < pages.length(); i++) {
			JSONObject page = (JSONObject) pages.get(i);
			if(r!=0) {
			builder.append("\n\n");
			}
			r++;
			JSONArray lines = (JSONArray) page.get("lines");
			for (int j = 0; j < lines.length(); j++) {
				JSONObject line = (JSONObject) lines.get(j);
				builder.append("\n" + line.get("text"));
			}
		}
		return builder;
	}
	
	 /**
     * 
     * @param filename file name to find eligibility
     * @param customOcrIncludes eligible includes
     * @return boolean. whether the custom ocr can process the file type or not
     */
    public static boolean isCustomOcrIncludes(String filename, List<String> customOcrIncludes) {

        logger.debug("filename = [{}], custom ocr includes = [{}]", filename, customOcrIncludes);
        // No rules ? Fine, we index everything
        if (customOcrIncludes == null || customOcrIncludes.isEmpty()) {
            logger.trace("no include rules");
            return true;
        }

        for (String include : customOcrIncludes) {
            String regex = include.toLowerCase().replace("?", ".?").replace("*", ".*?");
            logger.trace("regex is [{}]", regex);
            if (filename.toLowerCase().matches(regex)) {
                logger.trace("does match include regex");
                return true;
            }
        }

        logger.trace("does not match any include pattern");
        return false;
    }
    
}
