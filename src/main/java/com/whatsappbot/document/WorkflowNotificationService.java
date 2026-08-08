package com.whatsappbot.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.domain.tenant.TenantRepository;
import com.whatsappbot.infrastructure.whatsapp.WhatsAppGraphClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowNotificationService {
    private final WorkflowNotificationRepository repository;
    private final WorkflowNotificationEventService eventService;
    private final TenantRepository tenantRepository;
    private final WhatsAppGraphClient whatsApp;
    private final JavaMailSender mailSender;
    private final ObjectMapper mapper;

    @Value("${app.document-notifications.dispatch-batch-size:50}") private int dispatchBatchSize;
    @Value("${app.document-notifications.delivery-batch-size:50}") private int deliveryBatchSize;
    @Value("${app.document-notifications.max-attempts:5}") private int maxAttempts;
    @Value("${app.document-notifications.email-enabled:false}") private boolean emailEnabled;
    @Value("${app.document-notifications.whatsapp-enabled:true}") private boolean whatsappEnabled;
    @Value("${app.document-notifications.email-from:no-reply@document-control.local}") private String emailFrom;

    @Scheduled(fixedDelayString="${app.document-notifications.sla-scan-ms:600000}")
    public void scanSla(){
        try{eventService.enqueueSlaNotifications();}catch(Exception ex){log.error("Document notification SLA scan failed",ex);}
    }

    @Scheduled(fixedDelayString="${app.document-notifications.dispatch-ms:15000}")
    public void dispatch(){
        try{dispatchBatch();}catch(Exception ex){log.error("Document notification audience dispatch failed",ex);}
    }

    @Transactional
    public int dispatchBatch(){
        List<WorkflowNotificationRepository.OutboxRow> events=repository.claimOutbox(dispatchBatchSize);
        int recipients=0;
        for(var event:events){
            Message message=message(event);
            for(var user:repository.recipients(event)){
                repository.insertInApp(event,user,message.subject(),message.body());
                if(user.emailEnabled()&&user.email()!=null&&!user.email().isBlank())
                    repository.insertDelivery(event,user,"EMAIL",user.email(),message.subject(),message.body());
                if(user.whatsappEnabled()&&user.phone()!=null&&!user.phone().isBlank())
                    repository.insertDelivery(event,user,"WHATSAPP",user.phone(),message.subject(),message.body());
                recipients++;
            }
        }
        return recipients;
    }

    @Scheduled(fixedDelayString="${app.document-notifications.delivery-ms:30000}")
    public void deliver(){
        try{repository.recoverStuck();deliverBatch();}catch(Exception ex){log.error("Document notification delivery worker failed",ex);}
    }

    public int deliverBatch(){
        int processed=0;
        for(var delivery:repository.claimDeliveries(deliveryBatchSize,maxAttempts)){
            try{
                if("EMAIL".equals(delivery.channel())) sendEmail(delivery);
                else if("WHATSAPP".equals(delivery.channel())) sendWhatsApp(delivery);
                else throw new IllegalStateException("Unsupported notification channel "+delivery.channel());
                repository.sent(delivery.id());
            }catch(ChannelDisabledException disabled){
                repository.skipped(delivery.id(),disabled.getMessage());
            }catch(Exception ex){
                repository.failed(delivery.id(),delivery.attempts(),maxAttempts,rootMessage(ex));
                log.warn("Notification delivery failed. id={} channel={} attempt={}",delivery.id(),delivery.channel(),delivery.attempts()+1,ex);
            }
            processed++;
        }
        return processed;
    }

    private void sendEmail(WorkflowNotificationRepository.DeliveryRow d){
        if(!emailEnabled) throw new ChannelDisabledException("Email delivery disabled; set DOCUMENT_NOTIFICATION_EMAIL_ENABLED=true and SMTP settings");
        SimpleMailMessage m=new SimpleMailMessage();
        m.setFrom(emailFrom);m.setTo(d.destination());m.setSubject(d.subject());m.setText(d.body());
        mailSender.send(m);
    }

    private void sendWhatsApp(WorkflowNotificationRepository.DeliveryRow d){
        if(!whatsappEnabled) throw new ChannelDisabledException("WhatsApp document notifications disabled");
        var tenant=tenantRepository.findById(d.tenantId()).orElseThrow(()->new IllegalStateException("Tenant not found"));
        whatsApp.sendTextMessageChecked(tenant,d.destination(),d.subject()+"\n\n"+d.body());
    }

    private Message message(WorkflowNotificationRepository.OutboxRow event){
        JsonNode p;
        try{p=mapper.readTree(event.payload());}catch(Exception ex){p=mapper.createObjectNode();}
        String document=text(p,"documentCode",text(p,"title","Document"));
        String step=text(p,"stepName","approval stage");
        return switch(event.eventType()){
            case "APPROVAL_ASSIGNED" -> new Message("Approval action assigned",document+" requires your action at "+step+due(p)+".");
            case "APPROVAL_DUE_SOON" -> new Message("Approval due soon",document+" is due soon at "+step+due(p)+".");
            case "APPROVAL_OVERDUE" -> new Message("Approval overdue",document+" is overdue at "+step+due(p)+".");
            case "APPROVAL_RESULT" -> new Message("Approval result: "+text(p,"status","UPDATED"),document+" workflow completed with status "+text(p,"status","UPDATED")+outcome(p)+".");
            case "TRANSMITTAL_ISSUED" -> new Message("Transmittal issued: "+text(p,"transmittalNo","Transmittal"),text(p,"subject","A new transmittal has been issued")+" ("+text(p,"purpose","FOR_INFORMATION")+").");
            case "TRANSMITTAL_ACKNOWLEDGED" -> new Message("Transmittal acknowledged: "+text(p,"transmittalNo","Transmittal"),text(p,"subject","A recipient organization acknowledged the transmittal")+".");
            default -> new Message("Document control notification",event.eventType().replace('_',' ')+": "+document);
        };
    }

    private static String text(JsonNode p,String name,String fallback){JsonNode n=p.get(name);return n==null||n.isNull()||n.asText().isBlank()?fallback:n.asText();}
    private static String due(JsonNode p){String due=text(p,"dueAt","");return due.isBlank()?"":"; due "+due;}
    private static String outcome(JsonNode p){String o=text(p,"reviewOutcome","");return o.isBlank()?"":" ("+o+")";}
    private static String rootMessage(Throwable t){Throwable x=t;while(x.getCause()!=null)x=x.getCause();return x.getMessage()==null?x.getClass().getSimpleName():x.getMessage();}
    private record Message(String subject,String body){}
    private static final class ChannelDisabledException extends RuntimeException{ChannelDisabledException(String m){super(m);}}

    public List<WorkflowNotificationRepository.InAppView> mine(UUID tenantId,UUID userId,int limit){return repository.notifications(tenantId,userId,Math.max(1,Math.min(limit,200)));}
    public int unread(UUID tenantId,UUID userId){return repository.unreadCount(tenantId,userId);}
    public int markRead(UUID tenantId,UUID userId,UUID notificationId){return repository.markRead(tenantId,userId,notificationId);}
    public int markAllRead(UUID tenantId,UUID userId){return repository.markAllRead(tenantId,userId);}
    public WorkflowNotificationRepository.Preferences preferences(UUID tenantId,UUID userId){return repository.preferences(tenantId,userId);}
    public void preferences(UUID tenantId,UUID userId,boolean email,boolean whatsapp,String phone){repository.updatePreferences(tenantId,userId,email,whatsapp,phone);}
    public List<WorkflowNotificationRepository.DeliveryAudit> audit(UUID tenantId,int limit){return repository.deliveryAudit(tenantId,Math.max(1,Math.min(limit,500)));}
}
