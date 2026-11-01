CREATE TABLE `sdlc_system` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `base_url` varchar(255) NOT NULL,
  `description` varchar(255) DEFAULT NULL,
  `created_date` datetime NOT NULL,
  `last_modified_date` datetime NOT NULL,
  PRIMARY KEY (`ID`)
);

CREATE TABLE `project` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `external_id` varchar(255) NOT NULL,
  `sdlc_system_id` int(11) NOT NULL,
  `name` varchar(255),
  `created_date` datetime NOT NULL,
  `last_modified_date` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `system_project` (`sdlc_system_id`,`external_id`),
  CONSTRAINT `project_sdlc_system_fk` FOREIGN KEY (`sdlc_system_id`) REFERENCES `sdlc_system` (`id`)
);

-- Outbox (Spring Modulith event publication registry): an event is stored here in the transaction that
-- raised it, once per listener, and removed once that listener has handled it (e.g. sent it to RabbitMQ)
CREATE TABLE `event_publication` (
  `id` uuid NOT NULL,
  `listener_id` varchar(512) NOT NULL,
  `event_type` varchar(512) NOT NULL,
  `serialized_event` varchar(4000) NOT NULL,
  `publication_date` timestamp(9) with time zone NOT NULL,
  `completion_date` timestamp(9) with time zone DEFAULT NULL,
  `status` varchar(20),
  `completion_attempts` int,
  `last_resubmission_date` timestamp(9) with time zone DEFAULT NULL,
  PRIMARY KEY (`id`)
);
CREATE INDEX `event_publication_by_completion_date_idx` ON `event_publication` (`completion_date`);
