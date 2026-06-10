import { createApp } from 'vue'
import {
  ElButton,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElPopconfirm,
  ElRadioButton,
  ElRadioGroup,
  ElScrollbar,
  ElTabPane,
  ElTabs,
  ElTag,
  ElTree,
  ElUpload
} from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import './styles.css'

const app = createApp(App)

;[
  ElButton,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElPopconfirm,
  ElRadioButton,
  ElRadioGroup,
  ElScrollbar,
  ElTabPane,
  ElTabs,
  ElTag,
  ElTree,
  ElUpload
].forEach((component) => {
  app.use(component)
})

app.mount('#app')
