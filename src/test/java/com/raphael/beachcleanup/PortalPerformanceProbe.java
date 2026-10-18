package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import com.raphael.beachcleanup.services.*;

/** Reproducible query counts and descriptive timings; never gates CI on machine speed. */
final class PortalPerformanceProbe {
    JdbcTemplate jdbc;
    SearchService search;
    ImpactReportService reports;
    EventRegistrationService registrations;
    EntityManagerFactory factory;
    org.springframework.context.ApplicationContext context;

    static void run(org.springframework.context.ApplicationContext context, String label, String database) throws Exception {
        var probe = new PortalPerformanceProbe();
        probe.context=context;
        probe.jdbc=context.getBean(JdbcTemplate.class); probe.search=context.getBean(SearchService.class);
        probe.reports=context.getBean(ImpactReportService.class); probe.registrations=context.getBean(EventRegistrationService.class);
        probe.factory=context.getBean(EntityManagerFactory.class);
        probe.factory.unwrap(SessionFactory.class).getStatistics().setStatisticsEnabled(true);
        probe.measureRepresentativeVolume(label,database);
    }
    void measureRepresentativeVolume(String label, String database) throws Exception {
        jdbc.update("delete from tb_event_volunteer"); jdbc.update("delete from tb_trash_collection");
        jdbc.update("delete from tb_user"); jdbc.update("delete from tb_cleanup_event");
        for (int i=0;i<30;i++) jdbc.update("insert into tb_catalog_entry(id,kind,name,version,updated_at) values (?,?,?,?,CURRENT_TIMESTAMP)",
            100000+i,i<20?"beaches":"materials","Volume "+i,0);
        List<Object[]> users = new ArrayList<>(), events = new ArrayList<>(), records = new ArrayList<>(), links = new ArrayList<>();
        for (int i=0;i<200;i++) users.add(new Object[]{100000+i,"Volume "+i,"volume"+i+"@example.com","fixture", "VOLUNTEER",0,0,false});
        jdbc.batchUpdate("insert into tb_user(id,name,email,password,role,credential_version,row_version,email_verified) values (?,?,?,?,?,?,?,?)",users);
        for (int i=0;i<500;i++) {
            events.add(new Object[]{100000+i,"Evento "+i,"Volume "+(i%20),"2026-10-15 09:00:00","Agendado",0,100000+i%20});
            links.add(new Object[]{100000,100000+i});
        }
        jdbc.batchUpdate("insert into tb_cleanup_event(id,title,location,date_time,status,row_version,beach_id) values (?,?,?,?,?,?,?)",events);
        jdbc.batchUpdate("insert into tb_event_volunteer(volunteer_id,event_id) values (?,?)",links);
        for (int i=0;i<10000;i++) records.add(new Object[]{100000+i,"Volume "+(i%20),"Volume "+(20+i%10),"1.125","2026-10-15",100000+i%200,100000+i%500,0,100000+i%20,100020+i%10});
        jdbc.batchUpdate("insert into tb_trash_collection(id,beach,material_type,weight_kg,registration_date,volunteer_id,event_id,row_version,beach_id,material_id) values (?,?,?,?,?,?,?,?,?,?)",records);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("100000", "unused",List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            Map<String,Object> output = new LinkedHashMap<>();
            output.put("database",database);
            output.put("volume",Map.of("collections",10000,"events",500,"users",200,"registrations",500,"referencedCatalogEntries",30));
            output.put("events20",measure(() -> assertEquals(20,search.events(null,null,null,null,null,PageRequest.of(0,20)).content().size())));
            output.put("collections20",measure(() -> assertEquals(20,search.collections(null,null,null,null,null,null,null,null,PageRequest.of(0,20)).content().size())));
            output.put("impact",measure(() -> assertEquals(10000,reports.impact(null,null,null,null,null,"beach").totals().collections())));
            output.put("registrations20",measure(() -> {
                var result=registrations.findPage(100000L,List.of(100000L,100001L),PageRequest.of(0,20));
                assertEquals(20,result.content().size()); assertEquals(500,result.totalElements()); assertEquals(2,result.registeredEventIds().size());
            }));
            assertTrue(((Number)((Map<?,?>)output.get("events20")).get("sqlStatements")).longValue() <= 2);
            assertTrue(((Number)((Map<?,?>)output.get("collections20")).get("sqlStatements")).longValue() <= 3);
            assertThrows(com.raphael.beachcleanup.exceptions.FieldValidationException.class, () -> registrations.findMyEvents(100000L));
            assertThrows(com.raphael.beachcleanup.exceptions.FieldValidationException.class, () -> context.getBean(UserService.class).findAll());
            assertThrows(com.raphael.beachcleanup.exceptions.FieldValidationException.class, () -> context.getBean(CleanupEventService.class).findAll());
            var access=context.getBean(com.raphael.beachcleanup.security.AccessPolicy.class);
            assertThrows(com.raphael.beachcleanup.exceptions.FieldValidationException.class,access::visibleCollections);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("100000","unused",List.of(new SimpleGrantedAuthority("ROLE_VOLUNTEER"))));
            var own=access.visibleCollections();
            assertEquals(50,own.size(),"The global 10k records must not block an account's own 50 records");
            assertTrue(own.stream().allMatch(record -> record.getVolunteer().getId().equals(100000L)));

            jdbc.update("insert into tb_cleanup_event(id,title,location,date_time,status,row_version,beach_id) values (200000,'Limite','Volume 0','2026-10-15 09:00:00','Agendado',0,100000)");
            jdbc.update("insert into tb_trash_collection(id,beach,material_type,weight_kg,registration_date,volunteer_id,event_id,row_version,beach_id,material_id) values (200000,'Volume 0','Volume 20',1.125,'2026-10-15',100000,200000,0,100000,100020)");
            assertThrows(com.raphael.beachcleanup.exceptions.FieldValidationException.class, () -> reports.impact(null,null,null,null,null,"event"));
            assertThrows(com.raphael.beachcleanup.exceptions.FieldValidationException.class, () -> reports.participation(null,null,null,null));
            assertTrue(label.matches("[a-z-]+"));
            Path path=Path.of("target","portal-performance-"+label+".json");
            Files.writeString(path,new tools.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(output));
            System.out.println("Performance evidence: "+path.toAbsolutePath());
        } finally { SecurityContextHolder.clearContext(); }
    }
    Map<String,Object> measure(Runnable operation) {
        operation.run(); operation.run();
        long[] nanos=new long[9], queries=new long[9];
        var stats=factory.unwrap(SessionFactory.class).getStatistics();
        for(int i=0;i<9;i++) { stats.clear(); long begin=System.nanoTime(); operation.run(); nanos[i]=System.nanoTime()-begin; queries[i]=stats.getPrepareStatementCount(); }
        Arrays.sort(nanos); Arrays.sort(queries);
        return Map.of("samples",9,"medianMs",nanos[4]/1e6,"p95Ms",nanos[8]/1e6,"sqlStatements",queries[4]);
    }
}
