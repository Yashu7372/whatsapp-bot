package com.whatsappbot.application.automobile;

import com.whatsappbot.application.context.TenantExecutionContext;
import com.whatsappbot.domain.appointment.ServiceAppointmentEntity;
import com.whatsappbot.domain.appointment.ServiceAppointmentRepository;
import com.whatsappbot.domain.contact.ContactEntity;
import com.whatsappbot.domain.contact.ContactRepository;
import com.whatsappbot.domain.service.ServiceRecordEntity;
import com.whatsappbot.domain.service.ServiceRecordRepository;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.vehicle.VehicleEntity;
import com.whatsappbot.domain.vehicle.VehicleRepository;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component("automobileServiceTools")
@RequiredArgsConstructor
public class AutomobileServiceTools {

    private static final String STATUS_AVAILABLE = "AVAILABLE";
    private static final String STATUS_BOOKED = "BOOKED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final ContactRepository contactRepository;
    private final VehicleRepository vehicleRepository;
    private final ServiceRecordRepository serviceRecordRepository;
    private final ServiceAppointmentRepository appointmentRepository;

    @Tool("Look up an automobile service customer by phone number. Returns customer name, vehicles, and latest service details.")
    @Transactional(readOnly = true)
    public String lookupCustomerByPhone(String phone) {
        TenantExecutionContext.Context context = TenantExecutionContext.getRequired();
        Optional<ContactEntity> customer = findCustomer(context, phone);
        if (customer.isEmpty()) {
            return "NO_CUSTOMER_FOUND for phone=" + safe(phone);
        }
        return summarizeCustomer(context.tenant(), customer.get());
    }

    @Tool("List all registered vehicles for an automobile service customer phone number.")
    @Transactional(readOnly = true)
    public String listCustomerVehicles(String customerPhone) {
        TenantExecutionContext.Context context = TenantExecutionContext.getRequired();
        ContactEntity customer = findCustomer(context, customerPhone).orElse(context.contact());
        List<VehicleEntity> vehicles = vehicleRepository.findByTenantAndContactAndActiveTrueOrderByCreatedAtDesc(context.tenant(), customer);
        if (vehicles.isEmpty()) {
            return "NO_REGISTERED_VEHICLES for " + displayCustomer(customer);
        }
        StringBuilder result = new StringBuilder("REGISTERED_VEHICLES for ").append(displayCustomer(customer)).append(":\n");
        for (VehicleEntity vehicle : vehicles) {
            result.append("- ").append(vehicle.displayName());
            if (vehicle.getColor() != null && !vehicle.getColor().isBlank()) {
                result.append(" | color: ").append(vehicle.getColor());
            }
            result.append("\n");
        }
        return result.toString().trim();
    }

    @Tool("Get complete service history for a vehicle plate number. Use before discussing past work, due service, or warranty context.")
    @Transactional(readOnly = true)
    public String getVehicleServiceHistory(String plateNumber) {
        TenantExecutionContext.Context context = TenantExecutionContext.getRequired();
        Optional<VehicleEntity> vehicle = findVehicle(context.tenant(), plateNumber);
        if (vehicle.isEmpty()) {
            return "VEHICLE_NOT_FOUND for plate=" + safe(plateNumber);
        }
        List<ServiceRecordEntity> records = serviceRecordRepository.findByTenantAndVehicleOrderByServiceDateDesc(context.tenant(), vehicle.get());
        if (records.isEmpty()) {
            return "NO_SERVICE_HISTORY for " + vehicle.get().displayName();
        }
        StringBuilder result = new StringBuilder("SERVICE_HISTORY for ").append(vehicle.get().displayName()).append(":\n");
        for (ServiceRecordEntity record : records) {
            result.append(formatRecord(record)).append("\n");
        }
        return result.toString().trim();
    }

    @Tool("Get recent service history across all vehicles for an automobile service customer phone number.")
    @Transactional(readOnly = true)
    public String getCustomerServiceHistory(String customerPhone) {
        TenantExecutionContext.Context context = TenantExecutionContext.getRequired();
        ContactEntity customer = findCustomer(context, customerPhone).orElse(context.contact());
        List<ServiceRecordEntity> records = serviceRecordRepository.findTop20ByTenantAndContactOrderByServiceDateDesc(context.tenant(), customer);
        if (records.isEmpty()) {
            return "NO_SERVICE_HISTORY for " + displayCustomer(customer);
        }
        StringBuilder result = new StringBuilder("RECENT_SERVICE_HISTORY for ").append(displayCustomer(customer)).append(":\n");
        for (ServiceRecordEntity record : records) {
            result.append(formatRecord(record)).append("\n");
        }
        return result.toString().trim();
    }

    @Tool("List available automobile service appointment slots for a requested service type and preferred date in yyyy-MM-dd format.")
    @Transactional(readOnly = true)
    public String listAvailableSlots(String serviceType, String preferredDate) {
        TenantExecutionContext.Context context = TenantExecutionContext.getRequired();
        LocalDate date = parseDateOrToday(preferredDate, context.tenant());
        List<ServiceAppointmentEntity> slots = appointmentRepository
                .findTop10ByTenantAndAppointmentDateAndStatusOrderByTimeSlotAsc(context.tenant(), date, STATUS_AVAILABLE);
        if (slots.isEmpty()) {
            return "NO_AVAILABLE_SLOTS for date=" + date + ". Ask for another date. Friday and lunch break are closed.";
        }
        StringBuilder result = new StringBuilder("AVAILABLE_SLOTS for ")
                .append(humanize(serviceType)).append(" on ").append(date).append(":\n");
        for (ServiceAppointmentEntity slot : slots) {
            result.append("- ").append(slot.getTimeSlot()).append("\n");
        }
        return result.append("Use bookAppointment with exact date and timeSlot to reserve.").toString().trim();
    }

    @Tool("Book an automobile service appointment. Requires customer phone, plate number, service type, date yyyy-MM-dd, exact time slot, and customer name.")
    @Transactional
    public String bookAppointment(String customerPhone,
                                  String plateNumber,
                                  String serviceType,
                                  String date,
                                  String timeSlot,
                                  String customerName) {
        TenantExecutionContext.Context context = TenantExecutionContext.getRequired();
        LocalDate appointmentDate = parseRequiredDate(date);
        if (appointmentDate == null) {
            return "INVALID_DATE. Use yyyy-MM-dd.";
        }
        if (timeSlot == null || timeSlot.isBlank()) {
            return "INVALID_TIME_SLOT. Use an exact slot like 09:00-10:00.";
        }

        Optional<ServiceAppointmentEntity> slot = appointmentRepository
                .findByTenantAndAppointmentDateAndTimeSlot(context.tenant(), appointmentDate, timeSlot.trim());
        if (slot.isEmpty() || !slot.get().isAvailable()) {
            return "SLOT_NOT_AVAILABLE for " + appointmentDate + " " + timeSlot + ". Ask the customer to choose another slot.";
        }

        ContactEntity customer = findCustomer(context, customerPhone).orElse(context.contact());
        if (customerName != null && !customerName.isBlank()) {
            customer.setDisplayName(customerName.trim());
            contactRepository.save(customer);
        }

        VehicleEntity vehicle = null;
        if (plateNumber != null && !plateNumber.isBlank()) {
            Optional<VehicleEntity> vehicleMatch = findVehicle(context.tenant(), plateNumber);
            if (vehicleMatch.isEmpty()) {
                return "VEHICLE_NOT_FOUND for plate=" + plateNumber + ". Ask customer to confirm plate number.";
            }
            vehicle = vehicleMatch.get();
        }

        ServiceAppointmentEntity appointment = slot.get();
        appointment.setContact(customer);
        appointment.setVehicle(vehicle);
        appointment.setServiceType(normalizeServiceType(serviceType));
        appointment.setStatus(STATUS_BOOKED);
        appointment.setCustomerPhone(resolvePhone(customerPhone, context));
        appointment.setCustomerName(customer.getDisplayName());
        appointment.setNotes("Booked by WhatsApp AI assistant");
        ServiceAppointmentEntity saved = appointmentRepository.save(appointment);

        String vehicleText = vehicle == null ? "vehicle details pending" : vehicle.displayName();
        return "APPOINTMENT_BOOKED reference=" + saved.getId()
                + ", customer=" + displayCustomer(customer)
                + ", vehicle=" + vehicleText
                + ", service=" + humanize(saved.getServiceType())
                + ", date=" + saved.getAppointmentDate()
                + ", slot=" + saved.getTimeSlot();
    }

    @Tool("Cancel an automobile service appointment by appointment reference UUID.")
    @Transactional
    public String cancelAppointment(String appointmentId) {
        TenantExecutionContext.Context context = TenantExecutionContext.getRequired();
        UUID id;
        try {
            id = UUID.fromString(appointmentId);
        } catch (RuntimeException ex) {
            return "INVALID_APPOINTMENT_REFERENCE";
        }

        Optional<ServiceAppointmentEntity> appointment = appointmentRepository.findById(id);
        if (appointment.isEmpty() || !appointment.get().getTenant().getId().equals(context.tenant().getId())) {
            return "APPOINTMENT_NOT_FOUND";
        }
        if (STATUS_CANCELLED.equalsIgnoreCase(appointment.get().getStatus())) {
            return "APPOINTMENT_ALREADY_CANCELLED reference=" + appointmentId;
        }

        ServiceAppointmentEntity entity = appointment.get();
        entity.setStatus(STATUS_CANCELLED);
        entity.setNotes(appendNote(entity.getNotes(), "Cancelled by WhatsApp AI assistant"));
        appointmentRepository.save(entity);
        return "APPOINTMENT_CANCELLED reference=" + appointmentId;
    }

    private Optional<ContactEntity> findCustomer(TenantExecutionContext.Context context, String phone) {
        String normalized = normalizePhone(phone);
        if (!normalized.isBlank()) {
            return contactRepository.findByTenantAndPhoneNumber(context.tenant(), normalized);
        }
        return Optional.ofNullable(context.contact());
    }

    private Optional<VehicleEntity> findVehicle(TenantEntity tenant, String plateNumber) {
        if (plateNumber == null || plateNumber.isBlank()) {
            return Optional.empty();
        }
        String normalized = plateNumber.trim().toUpperCase();
        Optional<VehicleEntity> exact = vehicleRepository.findByTenantAndPlateNumberIgnoreCaseAndActiveTrue(tenant, normalized);
        if (exact.isPresent()) {
            return exact;
        }
        return vehicleRepository.findByTenantAndPlateNumberContainingIgnoreCaseAndActiveTrue(tenant, normalized)
                .stream()
                .findFirst();
    }

    private String summarizeCustomer(TenantEntity tenant, ContactEntity customer) {
        List<VehicleEntity> vehicles = vehicleRepository.findByTenantAndContactAndActiveTrueOrderByCreatedAtDesc(tenant, customer);
        StringBuilder result = new StringBuilder("CUSTOMER_FOUND: ").append(displayCustomer(customer)).append("\n");
        if (vehicles.isEmpty()) {
            return result.append("No registered vehicles.").toString();
        }
        result.append("Vehicles:\n");
        for (VehicleEntity vehicle : vehicles) {
            result.append("- ").append(vehicle.displayName());
            serviceRecordRepository.findTopByTenantAndVehicleOrderByServiceDateDesc(tenant, vehicle)
                    .ifPresent(record -> result.append(" | last service: ")
                            .append(humanize(record.getServiceType()))
                            .append(" on ").append(record.getServiceDate().toLocalDate())
                            .append(nextServiceText(record)));
            result.append("\n");
        }
        return result.toString().trim();
    }

    private String formatRecord(ServiceRecordEntity record) {
        StringBuilder line = new StringBuilder("- ")
                .append(record.getServiceDate().toLocalDate())
                .append(" | ").append(humanize(record.getServiceType()))
                .append(" | ").append(record.getVehicle().getPlateNumber())
                .append(" | ").append(money(record.getCost(), record.getCurrency()))
                .append(" | ").append(record.getDescription());
        if (record.getMileageAtService() != null) {
            line.append(" | mileage: ").append(record.getMileageAtService()).append(" km");
        }
        if (record.getNextServiceDate() != null) {
            line.append(" | next due: ").append(record.getNextServiceDate().toLocalDate());
        }
        if (record.getNotes() != null && !record.getNotes().isBlank()) {
            line.append(" | notes: ").append(record.getNotes());
        }
        return line.toString();
    }

    private String nextServiceText(ServiceRecordEntity record) {
        if (record.getNextServiceDate() == null) {
            return "";
        }
        return " | next due: " + record.getNextServiceDate().toLocalDate();
    }

    private LocalDate parseDateOrToday(String date, TenantEntity tenant) {
        LocalDate parsed = parseRequiredDate(date);
        return parsed != null ? parsed : LocalDate.now(zoneId(tenant));
    }

    private LocalDate parseRequiredDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private ZoneId zoneId(TenantEntity tenant) {
        try {
            return ZoneId.of(tenant.getTimezone());
        } catch (RuntimeException ex) {
            return ZoneId.of("Asia/Dubai");
        }
    }

    private String resolvePhone(String customerPhone, TenantExecutionContext.Context context) {
        String normalized = normalizePhone(customerPhone);
        if (!normalized.isBlank()) {
            return normalized;
        }
        return normalizePhone(context.customerPhoneNumber());
    }

    private String normalizePhone(String phone) {
        return phone == null ? "" : phone.replaceAll("[^0-9]", "");
    }

    private String normalizeServiceType(String serviceType) {
        if (serviceType == null || serviceType.isBlank()) {
            return "GENERAL_SERVICE";
        }
        return serviceType.trim().toUpperCase().replace(' ', '_');
    }

    private String displayCustomer(ContactEntity customer) {
        String name = customer.getDisplayName() == null || customer.getDisplayName().isBlank()
                ? "Customer"
                : customer.getDisplayName();
        return name + " (" + customer.getPhoneNumber() + ")";
    }

    private String humanize(String value) {
        if (value == null || value.isBlank()) {
            return "service";
        }
        return value.trim().replace('_', ' ').toLowerCase();
    }

    private String money(BigDecimal amount, String currency) {
        if (amount == null) {
            return "cost not recorded";
        }
        return amount.stripTrailingZeros().toPlainString() + " " + (currency == null ? "AED" : currency);
    }

    private String appendNote(String current, String note) {
        if (current == null || current.isBlank()) {
            return note;
        }
        return current + " | " + note;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
