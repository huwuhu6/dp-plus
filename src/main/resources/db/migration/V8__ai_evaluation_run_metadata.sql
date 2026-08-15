ALTER TABLE `ai_evaluation_run`
  ADD COLUMN `model` varchar(128) NOT NULL DEFAULT '' AFTER `user_id`,
  ADD COLUMN `retrieval_strategy_version` varchar(128) NOT NULL DEFAULT '' AFTER `model`,
  ADD COLUMN `evaluation_dataset_version` varchar(128) NOT NULL DEFAULT '' AFTER `retrieval_strategy_version`;
