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

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component("automobileServiceTools")
@RequiredArgsConstructor
public class AutomobileServiceTools {

    private static final String AVAILABLE = "AVAILABLE";
    private static final String BOOKED = "BOOKED";

    private final ContactRepository contactRepository;
    private final VehicleRepository vehicleRepository;
    private final ServiceRecordRepository serviceRecordRepository;
    private final ServiceAppointmentRepository appointmentRepository;

    @Tool("Look up an automobile service customer by WhatsApp phone number. Returns customer name, registered vehicles, and recent service summary. Use at the start of automobile conversations to greet returning customers.")
    public String lookupCustomerByPhone(String phone) {
        TenantExecutionContext.Context context = TenantExecutionContext.getRequired();
        TenantEntity tenant = context.tenant();
        Optional<ContactEntity> contact = findContact(tenant, firstNonBlank(phone, context.customerPhoneNumber()));
        if (contact.isEmpty()) {
            return "CUSTOMER_NOT_FOUND";
        }
        ContactEntity customer = contact.get();
        List<VehicleEntity> vehicles = vehicleRepository.findByTenantAndContactAndActiveTrue(tenant, customer);
        if (vehicles.isEmpty()) {
            return "CUSTOMER_FOUND_WITH_NO_REGISTERED_VEHICLES: " + displayName(customer);
        }
        return "CUSTOMER_FOUND: " + displayName(customer) + "\n" + vehicles.stream()
                .map(vehicle -> formatVehicle(vehicle) + lastServiceSummary(tenant, vehicle))
                .collect(Collectors.joining("\n"));
    }

    @Tool("List all active vehicles registered for a customer phone number.")
    public String listCustomerVehicles(String customerPhone) {
        TenantExecutionContext.Context context = TenantExecutionContext.getRequired();
        TenantEntity tenant = context.tenant();
        Optional<ContactEntity> contact = findContact(tenant, firstNonBlank(customerPhone, context.customerPhoneNumber()));
        if (contact.isEmpty()) {
            return "CUSTOMER_NOT_FOUND";
        }
        List<VehicleEntity> vehicles = vehicleRepository.findByTenantAndContactAndActiveTrue(tenant, contact.get());
        if (vehicles.isEmpty()) {
            return "NO_VEHICLES_REGISTERED";
        }
        return vehicles.stream().map(this::formatVehicle).collect(Collectors.joining("\n"));
    }

    @Tool("Get full service history for one vehicle by plate number. Returns dates, service types, cost, technician notes, and next recommended service date.")
    public String getVehicleServiceHistory(String plateNumber) {
        TenantExecutionContext.Context context = TenantExecutionContext.getRequired();
        TenantEntity tenant = context.tenant();
        Optional<VehicleEntity> vehicle = vehicleRepository.findByTenantAndPlateNumberIgnoreCaseAndActiveTrue(tenant, normalizePlate(plateNumber));
        if (vehicle.isEmpty()) {
            return "VEHICLE_NOT_FOUND_FOR_PLATE: " + plateNumber;
        }
        List<ServiceRecordEntity> records = serviceRecordRepository.findByTenantAndVehicleOrderByServiceDateDesc(tenant, vehicle.get());
        if (records.isEmpty()) {
            return "NO_SERVICE_HISTORY_FOUND_FOR: " + formatVehicle(vehicle.get());
        }
        return "SERVICE_HISTORY_FOR: " + formatVehicle(vehicle.get()) + "\n" + records.stream()
                .map(this::formatServiceRecord)
                .collect(Collectors.joining("\n"));
    }

    @Tool("Get recent service history across all vehicles for a customer phone number.")
    public String getCustomerServiceHistory(String customerPhone) {
        TenantExecutionContext.Context context = TenantExecutionContext.getRequired();
        TenantEntity tenant = context.tenant();
        Optional<ContactEntity> contact = findContact(tenant, firstNonBlank(customerPhone, context.customerPhoneNumber()));
        if (contact.isEmpty()) {
            return "CUSTOMER_NOT_FOUND";
        }
        List<ServiceRecordEntity> records = serviceRecordRepository.findTop10ByTenantAndContactOrderByServiceDateDesc(tenant, contact.get());
        if (records.isEmpty()) {
            return "NO_SERVICE_HISTORY_FOUND_FOR_CUSTOMER: " + displayName(contact.get());
        }
        return records.stream().map(this::formatServiceRecordWithVehicle).collect(Collectors.joining("\n"));
    }

    @Tool("List available appointment slots for an automobile service type and preferred date. preferredDate must be yyyy-MM-dd; if blank or invalid, today's date is used.")
    public String listAvailableSlots(String serviceType, String preferredDate) {
        TenantExecutionContext.Context context = TenantExecutionContext.getRequired();
        TenantEntity tenant = context.tenant();
        LocalDate date = parseDateOrToday(preferredDate);
        String requestedService = normalizeServiceType(serviceType);
        List<ServiceAppointmentEntity> slots = appointmentRepository
                .findByTenantAndServiceTypeIgnoreCaseAndAppointmentDateAndStatusOrderByTimeSlotAsc(
                        tenant, requestedService, date, AVAILABLE);
        if (slots.isEmpty()) {
            slots = appointmentRepository.findByTenantAndAppointmentDateAndStatusOrderByTimeSlotAsc(tenant, date, AVAILABLE);
        }
        if (slots.isEmpty()) {
            return "NO_AVAILABLE_SLOTS_FOR_DATE: " + date;
        }
        return "AVAILABLE_SLOTS_FOR_" + date + ":\n" + slots.stream()
                .limit(10)
                .map(slot -> slot.getTimeSlot() + " - " + slot.getServiceType() + " - appointmentId=" + slot.getId())
                .collect(Collectors.joining("\n"));
    }

    @Transactional
    @Tool("Book an automobile service appointment. date must be yyyy-MM-dd and timeSlot must match an available slot such as 09:00-10:00. Returns confirmation reference.")
    public String bookAppointment(String customerPhone,
                                  String plateNumber,
                                  String serviceType,
                                  String date,
                                  String timeSlot,
                                  String customerName) {
        TenantExecutionContext.Context context = TenantExecutionContext.getRequired();
        TenantEntity tenant = context.tenant();
        LocalDate appointmentDate = parseDateOrToday(date);
        Optional<ServiceAppointmentEntity> slot = appointmentRepository.findByTenantAndAppointmentDateAndTimeSlot(
                tenant, appointmentDate, timeSlot);
        if (slot.isEmpty()) {
            return "APPOINTMENT_SLOT_NOT_FOUND: " + appointmentDate + " " + timeSlot;
        }
        ServiceAppointmentEntity appointment = slot.get();
        if (!appointment.isAvailable()) {
            return "APPOINTMENT_SLOT_NOT_AVAILABLE: status=" + appointment.getStatus();
        }

        String phone = firstNonBlank(customerPhone, context.customerPhoneNumber());
        Optional<ContactEntity> contact = findContact(tenant, phone);
        Optional<VehicleEntity> vehicle = vehicleRepository.findByTenantAndPlateNumberIgnoreCaseAndActiveTrue(tenant, normalizePlate(plateNumber));
        String name = firstNonBlank(customerName, contact.map(this::displayName).orElse(null));
        String requestedService = normalizeServiceType(serviceType);

        appointment.book(
                contact.orElse(null),
                vehicle.orElse(null),
                requestedService,
                phone,
                name,
                "Booked by AI assistant"
        );
        ServiceAppointmentEntity saved = appointmentRepository.save(appointment);
        return "APPOINTMENT_BOOKED: reference=" + saved.getId()
                + ", date=" + saved.getAppointmentDate()
                + ", slot=" + saved.getTimeSlot()
                + ", service=" + saved.getServiceType();
    }

    @Transactional
    @Tool("Cancel an existing automobile service appointment by appointment UUID/reference.")
    public String cancelAppointment(String appointmentId) {
        TenantExecutionContext.Context context = TenantExecutionContext.getRequired();
        UUID id;
        try {
            id = UUID.fromString(appointmentId);
        } catch (IllegalArgumentException ex) {
            return "INVALID_APPOINTMENT_ID: " + appointmentId;
        }
        Optional<ServiceAppointmentEntity> appointment = appointmentRepository.findById(id)
                .filter(item -> item.getTenant().getId().equals(context.tenant().getId()));
        if (appointment.isEmpty()) {
            return "APPOINTMENT_NOT_FOUND";
        }
        appointment.get().cancel();
        appointmentRepository.save(appointment.get());
        return "APPOINTMENT_CANCELLED: " + appointmentId;
    }

    private Optional<ContactEntity> findContact(TenantEntity tenant, String phone) {
        String normalized = normalizePhone(phone);
        if (normalized == null || normalized.isBlank()) {
            return Optional.empty();
        }
        Optional<ContactEntity> byPhone = contactRepository.findByTenantAndPhoneNumber(tenant, normalized);
        return byPhone.isPresent() ? byPhone : contactRepository.findByTenantAndWaId(tenant, normalized);
    }

    private String lastServiceSummary(TenantEntity tenant, VehicleEntity vehicle) {
        List<ServiceRecordEntity> recent = serviceRecordRepository.findTop5ByTenantAndVehicleOrderByServiceDateDesc(tenant, vehicle);
        if (recent.isEmpty()) {
            return " | lastService=none";
        }
        ServiceRecordEntity last = recent.get(0);
        String next = last.getNextServiceDate() == null ? "no next date" : "next recommended " + last.getNextServiceDate();
        return " | lastService=" + last.getServiceDate() + " " + last.getServiceType() + " (" + next + ")";
    }

    private String formatVehicle(VehicleEntity vehicle) {
        return vehicle.getMake() + " " + vehicle.getModel()
                + (vehicle.getYear() == null ? "" : " " + vehicle.getYear())
                + " [" + vehicle.getPlateNumber() + "]";
    }

    private String formatServiceRecord(ServiceRecordEntity record) {
        return record.getServiceDate() + " | " + record.getServiceType()
                + " | " + nullSafe(record.getDescription())
                + " | cost=" + (record.getCost() == null ? "N/A" : record.getCost() + " " + record.getCurrency())
                + " | technician=" + nullSafe(record.getTechnicianName())
                + " | next=" + (record.getNextServiceDate() == null ? "N/A" : record.getNextServiceDate())
                + " | notes=" + nullSafe(record.getNotes());
    }

    private String formatServiceRecordWithVehicle(ServiceRecordEntity record) {
        return formatVehicle(record.getVehicle()) + " | " + formatServiceRecord(record);
    }

    private LocalDate parseDateOrToday(String value) {
        if (value == null || value.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            return LocalDate.now();
        }
    }

    private String normalizeServiceType(String serviceType) {
        return firstNonBlank(serviceType, "GENERAL_SERVICE").trim().replace(' ', '_').toUpperCase();
    }

    private String normalizePhone(String phone) {
        return phone == null ? null : phone.trim().replace("+", "").replace(" ", "");
    }

    private String normalizePlate(String plateNumber) {
        return plateNumber == null ? "" : plateNumber.trim().toUpperCase();
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private String displayName(ContactEntity contact) {
        return contact.getDisplayName() == null || contact.getDisplayName().isBlank()
                ? contact.getPhoneNumber()
                : contact.getDisplayName();
    }

    private String nullSafe(String value) {
        return value == null || value.isBlank() ? "N/A" : value;
    }
}
