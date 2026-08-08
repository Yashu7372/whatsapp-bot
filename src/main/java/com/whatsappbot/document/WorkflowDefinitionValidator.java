package com.whatsappbot.document;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Component
@RequiredArgsConstructor
public class WorkflowDefinitionValidator {
    private static final Set<String> AUTHORITIES=Set.of("INTERNAL_REVIEW","TECHNICAL_REVIEW","CLIENT_APPROVAL","COMMERCIAL_CERTIFICATION");
    private static final Set<String> ASSIGNMENTS=Set.of("USER","ORGANIZATION","PARTY_ROLE");
    private static final Set<String> PARTY_ROLES=Set.of("CLIENT","CONSULTANT","CONTRACTOR","SUBCONTRACTOR");
    private final ObjectMapper mapper;

    public void validate(String json){
        if(json==null)return;
        List<Map<String,Object>> steps;
        try{steps=mapper.readValue(json,new TypeReference<>(){});}catch(Exception ex){throw bad("Workflow steps must be a valid JSON array");}
        if(steps.isEmpty())throw bad("Workflow must contain at least one step");
        Map<String,Integer> first=new HashMap<>(),last=new HashMap<>();Map<String,String> groupAuthority=new HashMap<>();
        for(int i=0;i<steps.size();i++){
            Map<String,Object>s=steps.get(i);String authority=upper(s.get("authority"));
            if(authority==null||!AUTHORITIES.contains(authority))throw bad("Step "+(i+1)+" has invalid authority");
            String assignment=upper(s.get("assignmentType"));if(assignment==null)assignment=s.get("reviewerEmail")!=null?"USER":"PARTY_ROLE";
            if(!ASSIGNMENTS.contains(assignment))throw bad("Step "+(i+1)+" has invalid assignmentType");
            if("USER".equals(assignment)&&blank(s.get("reviewerEmail")))throw bad("Step "+(i+1)+" USER assignment requires reviewerEmail");
            if("ORGANIZATION".equals(assignment)&&blank(s.get("organizationId")))throw bad("Step "+(i+1)+" ORGANIZATION assignment requires organizationId");
            if("PARTY_ROLE".equals(assignment)){
                String role=upper(s.get("partyRole"));if(role==null||!PARTY_ROLES.contains(role))throw bad("Step "+(i+1)+" PARTY_ROLE assignment requires a valid partyRole");
                if("INTERNAL_REVIEW".equals(authority)&&!("CONTRACTOR".equals(role)||"SUBCONTRACTOR".equals(role)))throw bad("INTERNAL_REVIEW must be assigned to CONTRACTOR or SUBCONTRACTOR");
                if("TECHNICAL_REVIEW".equals(authority)&&!"CONSULTANT".equals(role))throw bad("TECHNICAL_REVIEW must be assigned to CONSULTANT");
                if("CLIENT_APPROVAL".equals(authority)&&!"CLIENT".equals(role))throw bad("CLIENT_APPROVAL must be assigned to CLIENT");
            }
            Object sla=s.get("slaHours");if(sla!=null){try{if(Integer.parseInt(String.valueOf(sla))<=0)throw bad("Step "+(i+1)+" slaHours must be positive");}catch(NumberFormatException ex){throw bad("Step "+(i+1)+" slaHours must be an integer");}}
            String group=string(s.get("parallelGroup"));boolean required=!Boolean.FALSE.equals(s.get("required"));
            if(!required&&group==null)throw bad("Optional steps must belong to a parallelGroup so they cannot block sequential progression");
            if(group!=null){first.putIfAbsent(group,i);last.put(group,i);String existing=groupAuthority.putIfAbsent(group,authority);if(existing!=null&&!existing.equals(authority))throw bad("All steps in parallelGroup "+group+" must use the same authority type");}
        }
        for(var e:first.entrySet()){
            String g=e.getKey();for(int i=e.getValue();i<=last.get(g);i++)if(!g.equals(string(steps.get(i).get("parallelGroup"))))throw bad("parallelGroup "+g+" must be contiguous");
            boolean anyRequired=false;for(int i=e.getValue();i<=last.get(g);i++)if(!Boolean.FALSE.equals(steps.get(i).get("required")))anyRequired=true;
            if(!anyRequired)throw bad("parallelGroup "+g+" must contain at least one required reviewer");
        }
    }
    private static String upper(Object o){return o==null?null:String.valueOf(o).trim().toUpperCase();}
    private static String string(Object o){if(o==null||String.valueOf(o).isBlank())return null;return String.valueOf(o).trim();}
    private static boolean blank(Object o){return o==null||String.valueOf(o).isBlank();}
    private static ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
}
